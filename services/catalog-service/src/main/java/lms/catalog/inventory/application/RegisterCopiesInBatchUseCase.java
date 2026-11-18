package lms.catalog.inventory.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lms.catalog.inventory.domain.AcquisitionSource;
import lms.catalog.inventory.domain.BookCopy;
import lms.catalog.inventory.domain.BookCopyRepository;
import lms.catalog.inventory.domain.ConditionGrade;
import lms.catalog.inventory.domain.CopyAcquisition;
import lms.catalog.inventory.domain.CopyAvailableEvent;
import lms.catalog.inventory.domain.CopyBatchConflictException;
import lms.catalog.inventory.domain.InvalidCopyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nhập bản sao theo lô cho nhập Excel (ADR-0024): kiểm bằng domain ({@link BookCopy#register},
 * {@link CopyAcquisition}), kiểm trùng mã vạch bằng MỘT truy vấn cho cả lô, ghi bằng một lần insert theo batch. Catalog
 * gọi vào đây (đúng hướng phụ thuộc): đầu sách và bản sao của chúng nằm trong cùng giao dịch chunk của catalog.
 * Sự kiện {@link CopyAvailableEvent} phát SAU commit qua {@link #publishAvailable} (best-effort, ADR-0001).
 */
public class RegisterCopiesInBatchUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegisterCopiesInBatchUseCase.class);
    /** Một lần gửi sự kiện lâu hơn mức này (1 giây) coi là Kafka không khả dụng. */
    static final long SLOW_PUBLISH_NANOS = 1_000_000_000L;

    private final BookCopyRepository repository;
    private final PublishInventoryEventPort eventPort;
    private final Clock clock;

    public RegisterCopiesInBatchUseCase(BookCopyRepository repository, PublishInventoryEventPort eventPort, Clock clock) {
        this.repository = repository;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    /** Một bản sao sẽ ghi: đầu sách đã có id. */
    public record NewCopy(UUID titleId, CopyDraft draft) {
    }

    /** Bản sao đã tạo (để phát sự kiện sau commit). */
    public record CreatedCopy(UUID copyId, UUID titleId) {
    }

    /** Kết quả: bản sao đã ghi, và chỉ số (theo thứ tự đầu vào) của dòng bị bỏ qua vì mã vạch đã có. */
    public record Result(List<CreatedCopy> created, Set<Integer> skippedDuplicateIndexes) {
    }

    /** Thông điệp lỗi nghiệp vụ của bản sao theo domain, hoặc null nếu hợp lệ. Không truy cập DB. */
    public String check(CopyDraft draft) {
        try {
            toDomain(UUID.randomUUID(), draft);
            return null;
        } catch (InvalidCopyException | IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** Mã vạch đã có trong tenant thuộc tập đã cho (một truy vấn, chia lô ở adapter). */
    public Set<String> existingBarcodes(Collection<String> barcodes) {
        return barcodes.isEmpty() ? Set.of() : repository.findExistingBarcodes(barcodes);
    }

    /**
     * Kiểm lại trùng mã vạch với DB hiện tại (một truy vấn) rồi ghi các bản sao còn lại theo batch. Dữ liệu phải đã qua
     * {@link #check}; vi phạm domain ném InvalidCopyException (lỗi lập trình, chunk hỏng). Ném
     * {@link CopyWriteConflictException} khi trùng khóa do ghi đồng thời.
     */
    @Transactional
    public Result registerAll(List<NewCopy> copies) {
        Set<String> existing = existingBarcodes(copies.stream().map(c -> c.draft().barcode().trim()).toList());
        List<BookCopy> toInsert = new ArrayList<>();
        Set<Integer> skipped = new java.util.HashSet<>();
        Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < copies.size(); i++) {
            NewCopy c = copies.get(i);
            String barcode = c.draft().barcode().trim();
            if (existing.contains(barcode) || !seen.add(barcode)) {
                skipped.add(i);
                continue;
            }
            toInsert.add(toDomain(c.titleId(), c.draft()));
        }
        try {
            repository.insertAll(toInsert);
        } catch (CopyBatchConflictException e) {
            throw new CopyWriteConflictException(e);
        }
        return new Result(toInsert.stream().map(b -> new CreatedCopy(b.id(), b.titleId())).toList(), skipped);
    }

    /**
     * Phát CopyAvailableEvent cho từng bản sao đã tạo (gọi SAU khi giao dịch ghi đã commit). Best-effort (ADR-0001):
     * publisher nuốt lỗi Kafka nhưng mỗi lần gửi có thể chặn tới {@code max.block.ms} khi broker không khả dụng, nên một
     * lần gửi chậm hơn {@link #SLOW_PUBLISH_NANOS} thì bỏ phần còn lại của lô (tránh giam luồng nhập hàng giờ).
     * @return số sự kiện đã thử phát
     */
    public int publishAvailable(Collection<CreatedCopy> created) {
        Instant now = Instant.now(clock);
        // Một truy vấn titleId -> workId cho cả lô (không theo từng bản sao, ADR-0026 3.2)
        java.util.Map<UUID, BookCopyRepository.TitleWork> works;
        try {
            works = repository.findTitleWorksByTitleIds(created.stream().map(CreatedCopy::titleId).distinct().toList());
        } catch (RuntimeException e) {
            // best-effort (ADR-0001): thiếu workId thì consumer rơi về hàng đợi theo titleId như event cũ
            log.warn("Không tra được workId cho lô CopyAvailableEvent: phát không kèm workId", e);
            works = java.util.Map.of();
        }
        int attempted = 0;
        for (CreatedCopy c : created) {
            long start = System.nanoTime();
            BookCopyRepository.TitleWork w = works.get(c.titleId());
            eventPort.publish(new CopyAvailableEvent(UUID.randomUUID(), c.copyId(), c.titleId(), now,
                    w == null ? null : w.workId(), w == null ? null : w.language()));
            attempted++;
            if (System.nanoTime() - start > SLOW_PUBLISH_NANOS) {
                log.warn("Kafka chậm hoặc không khả dụng: bỏ phát {} sự kiện CopyAvailableEvent còn lại của lô (best-effort)",
                        created.size() - attempted);
                break;
            }
        }
        return attempted;
    }

    private static BookCopy toDomain(UUID titleId, CopyDraft d) {
        AcquisitionSource source = parse(AcquisitionSource.class, d.acquisitionSource(), "Nguồn nhập");
        ConditionGrade grade = parse(ConditionGrade.class, d.conditionGrade(), "Tình trạng");
        CopyAcquisition acquisition = new CopyAcquisition(d.acquiredDate(), source, d.purchasePrice(), grade,
                d.conditionNote(), d.lastInventoryDate());
        return BookCopy.register(titleId, d.barcode(), d.shelfLocationId(), acquisition);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw, String label) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidCopyException(label + " không hợp lệ");
        }
    }
}

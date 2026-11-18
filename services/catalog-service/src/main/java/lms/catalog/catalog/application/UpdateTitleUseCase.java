package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.DuplicateIsbnException;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleNotFoundException;
import lms.catalog.catalog.domain.TitleRepository;
import lms.catalog.catalog.domain.Work;
import lms.catalog.catalog.domain.WorkRepository;
import lms.catalog.inventory.application.CopyAvailabilityQuery;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sửa thông tin ấn bản (tên, tác giả, ISBN, giá bìa, thông tin bổ sung, nhãn phần). Không đụng bản sao (book_copies).
 * Tương thích ngược (ADR-0026): trường cấp tác phẩm gửi kèm (categoryId, authorIds, extras.series, extras.seriesNumber) ghi
 * THẲNG vào tác phẩm của ấn bản (mọi ấn bản cùng tác phẩm cùng đổi) và được ghi gương sang cột cũ của mọi ấn bản trong
 * cùng transaction. Ấn bản chưa gắn tác phẩm (dữ liệu trước khi backfill) được tạo tác phẩm 1:1 ngay lần ghi này.
 */
public class UpdateTitleUseCase {

    private final TitleRepository repository;
    private final CopyAvailabilityQuery availability;
    private final TitleClassificationService classification;
    private final WorkRepository works;
    private final WorkService workService;
    private final TitleResponseAssembler assembler;

    public UpdateTitleUseCase(TitleRepository repository, CopyAvailabilityQuery availability) {
        this(repository, availability, null);
    }

    public UpdateTitleUseCase(TitleRepository repository, CopyAvailabilityQuery availability,
                              TitleClassificationService classification) {
        this(repository, availability, classification, null, null,
                new TitleResponseAssembler(availability, classification, null));
    }

    public UpdateTitleUseCase(TitleRepository repository, CopyAvailabilityQuery availability,
                              TitleClassificationService classification, WorkRepository works,
                              WorkService workService, TitleResponseAssembler assembler) {
        this.repository = repository;
        this.availability = availability;
        this.classification = classification;
        this.works = works;
        this.workService = workService;
        this.assembler = assembler;
    }

    @Transactional
    public TitleResponse execute(UpdateTitleCommand command) {
        Title current = repository.findById(command.id()).orElseThrow(() -> new TitleNotFoundException(command.id()));
        Title updated = current.update(command.name(), command.author(), command.isbn(), command.coverPrice())
                .withExtras(command.extras()).withPart(command.partNumber(), command.partLabel());
        if (updated.isbn() != null && repository.existsByIsbnAndIdNot(updated.isbn(), updated.id())) {
            throw new DuplicateIsbnException(updated.isbn());
        }
        if (classification != null) {
            // Thay toàn bộ phân loại chuẩn; giá trị đang có trên đầu sách vẫn được giữ dù mục đã ngừng dùng.
            updated = classification.classify(updated, command.publisherId(), command.categoryId(),
                    command.authorIds());
        }
        if (workService != null) {
            updated = writeWorkLevel(current, updated);
        }
        Title saved = repository.save(updated);
        if (classification == null) {
            long available = availability.countAvailable(List.of(saved.id())).getOrDefault(saved.id(), 0L);
            return TitleResponse.from(saved, available);
        }
        return assembler.assemble(List.of(saved), null).get(0);
    }

    /** Ghi trường cấp tác phẩm vào work (tạo work 1:1 nếu chưa có) và ghi gương lên mọi ấn bản của work. */
    private Title writeWorkLevel(Title current, Title updated) {
        Work work = current.workId() == null ? null : works.findById(current.workId()).orElse(null);
        if (work == null) {
            Work created = workService.createFromTitle(updated);
            return updated.withWork(created.id());
        }
        String series = updated.extras().series();
        UUID seriesId = series == null ? null : workService.resolveSeriesIdByName(series);
        Integer seriesNumber = seriesId == null ? null : updated.extras().seriesNumber();
        Work next = work.withClassification(updated.categoryId(), updated.authorIds(), seriesId, seriesNumber);
        if (!sameClassification(work, next)) {
            workService.mirror(works.save(next));
        }
        return updated;
    }

    private static boolean sameClassification(Work a, Work b) {
        return Objects.equals(a.categoryId(), b.categoryId()) && a.authorIds().equals(b.authorIds())
                && Objects.equals(a.seriesId(), b.seriesId()) && Objects.equals(a.seriesNumber(), b.seriesNumber());
    }
}

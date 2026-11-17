package lms.circulation.borrowing.application;

import lms.circulation.borrowing.domain.BorrowRule;
import lms.circulation.borrowing.domain.BorrowRuleRepository;
import lms.circulation.borrowing.domain.BorrowingDomainService;
import lms.circulation.borrowing.domain.BorrowingRecord;
import lms.circulation.borrowing.domain.BorrowingRepository;
import lms.circulation.borrowing.domain.CopyStatus;
import lms.circulation.borrowing.domain.NotFoundException;
import lms.circulation.policy.application.CirculationPolicyResponse;
import lms.circulation.policy.application.GetCirculationPolicyUseCase;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC06 - Lập phiếu mượn (QĐ1, QĐ2). Kiểm tra lại toàn bộ điều kiện lúc xác nhận, rồi trong một transaction:
 * lưu phiếu (unique index chặn một bản sao thuộc hai lượt mượn đang mở), chuyển đặt trước của chính độc giả sang
 * FULFILLED, và đánh dấu bản sao LOANED ở catalog. Gọi catalog làm bước cuối; nếu một bản sao lỗi thì hoàn tác
 * trạng thái các bản sao đã đổi và transaction rollback.
 * Lưu ý production: nên dùng Transactional Outbox thay cho publish trực tiếp (ADR-0001).
 */
public class BorrowBookUseCase {

    private static final Logger log = LoggerFactory.getLogger(BorrowBookUseCase.class);

    private final BorrowingDomainService domainService;
    private final BorrowingRepository repository;
    private final SaveBorrowingPort savePort;
    private final BorrowRuleRepository rules;
    private final ReaderDirectoryPort readers;
    private final CopyInventoryPort inventory;
    private final CopyReservationPort reservations;
    private final FineLedgerPort fines;
    private final PublishBorrowingEventPort eventPort;
    private final Clock clock;
    private final GetCirculationPolicyUseCase policies;

    public BorrowBookUseCase(BorrowingDomainService domainService, BorrowingRepository repository,
                             SaveBorrowingPort savePort, BorrowRuleRepository rules, ReaderDirectoryPort readers,
                             CopyInventoryPort inventory, CopyReservationPort reservations, FineLedgerPort fines,
                             PublishBorrowingEventPort eventPort, Clock clock) {
        this(domainService, repository, savePort, rules, readers, inventory, reservations, fines, eventPort, clock,
                GetCirculationPolicyUseCase.defaultsOnly());
    }

    public BorrowBookUseCase(BorrowingDomainService domainService, BorrowingRepository repository,
                             SaveBorrowingPort savePort, BorrowRuleRepository rules, ReaderDirectoryPort readers,
                             CopyInventoryPort inventory, CopyReservationPort reservations, FineLedgerPort fines,
                             PublishBorrowingEventPort eventPort, Clock clock, GetCirculationPolicyUseCase policies) {
        this.policies = policies;
        this.domainService = domainService;
        this.repository = repository;
        this.savePort = savePort;
        this.rules = rules;
        this.readers = readers;
        this.inventory = inventory;
        this.reservations = reservations;
        this.fines = fines;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    @Transactional
    public BorrowingResponse execute(BorrowBookCommand command) {
        UUID userId = command.userId();
        LocalDate today = LocalDate.now(clock);

        ReaderDirectoryPort.ReaderInfo reader = readers.find(userId)
                .orElseThrow(() -> new NotFoundException("READER_NOT_FOUND", "Không tìm thấy hồ sơ độc giả: " + userId));
        domainService.checkReaderEligible(reader.cardValid(),
                repository.countOverdueItemsByUserId(userId, today), fines.unpaidTotal(userId));
        // Tenant đã cấu hình chính sách (custom) thì số ngày mượn và hạn mức lấy từ chính sách, áp cho mọi loại độc giả;
        // chưa cấu hình thì giữ nguyên quy định theo loại độc giả (borrow_rules). Chỉ áp cho phiếu mới lập.
        CirculationPolicyResponse policy = policies.execute();
        BorrowRule rule = policy.custom()
                ? new BorrowRule(reader.readerType(), policy.maxActiveLoansPerReader(), policy.loanDays())
                : rules.findByReaderType(reader.readerType()).orElseThrow(() -> new NotFoundException(
                        "BORROW_RULE_NOT_FOUND", "Chưa cấu hình quy định mượn cho " + reader.readerType()));

        long active = repository.countActiveItemsByUserId(userId);
        BorrowingRecord record = domainService.borrow(rule, userId, command.bookCopyIds(), active, today);

        Map<UUID, CopyStatus> original = new HashMap<>();
        Map<UUID, UUID> titleByCopy = new HashMap<>();
        for (UUID copyId : command.bookCopyIds()) {
            CopyInventoryPort.CopyInfo copy = CopyResolver.requireCopy(copyId, inventory);
            Optional<UUID> holder = reservations.holderOf(copyId);
            domainService.checkCopyBorrowable(copyId, copy.status(), holder.orElse(null), userId);
            original.put(copyId, copy.status());
            if (copy.titleId() != null) titleByCopy.put(copyId, copy.titleId());
        }
        // Snapshot đầu sách vào dòng mượn (ADR-0018: thống kê top sách mượn nhóm theo đầu sách).
        record.attachTitles(titleByCopy);

        BorrowingRecord saved = savePort.save(record);

        List<UUID> changed = new ArrayList<>();
        try {
            for (UUID copyId : command.bookCopyIds()) {
                reservations.fulfill(copyId, userId);
                inventory.changeStatus(copyId, CopyStatus.LOANED);
                changed.add(copyId);
            }
        } catch (RuntimeException e) {
            compensate(changed, original);
            throw e;
        }

        record.pullEvents().forEach(eventPort::publish);
        return BorrowingMapper.toResponse(saved);
    }

    /** Hoàn tác trạng thái các bản sao đã đánh dấu LOANED (best-effort) khi lập phiếu thất bại giữa chừng. */
    private void compensate(List<UUID> changed, Map<UUID, CopyStatus> original) {
        for (UUID copyId : changed) {
            try {
                inventory.changeStatus(copyId, original.get(copyId));
            } catch (RuntimeException ex) {
                log.error("Không hoàn tác được trạng thái bản sao {} về {} sau khi lập phiếu lỗi", copyId, original.get(copyId), ex);
            }
        }
    }
}

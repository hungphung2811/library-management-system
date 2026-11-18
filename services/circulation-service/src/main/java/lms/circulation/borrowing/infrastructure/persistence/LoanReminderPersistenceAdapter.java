package lms.circulation.borrowing.infrastructure.persistence;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.circulation.borrowing.application.LoanReminderCandidate;
import lms.circulation.borrowing.application.LoanReminderPort;
import lms.circulation.borrowing.application.LoanReminderRecord;
import lms.circulation.borrowing.domain.LoanReminderKind;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

/**
 * Cài đặt LoanReminderPort bằng JPQL (tenant lọc bởi @TenantId + RLS). Mỗi lô chỉ tốn: một truy vấn chọn phiếu (items nạp
 * theo lô nhờ @BatchSize), một truy vấn IN lấy lần nhắc đã có (chỉ ở nhánh quá hạn) và một lệnh ghi/flush.
 */
@Repository
class LoanReminderPersistenceAdapter implements LoanReminderPort {

    private final SpringDataLoanReminderRepository jpa;

    LoanReminderPersistenceAdapter(SpringDataLoanReminderRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<LoanReminderCandidate> findDueSoon(LocalDate from, LocalDate to, UUID afterId, int limit) {
        List<BorrowingJpaEntity> rows = jpa.findDueSoon(from, to, afterId, LoanReminderKind.DUE_SOON,
                PageRequest.of(0, limit));
        List<LoanReminderCandidate> result = new ArrayList<>(rows.size());
        for (BorrowingJpaEntity b : rows) {
            result.add(new LoanReminderCandidate(b.getId(), b.getUserId(), b.getDueDate(), openItems(b), 0));
        }
        return result;
    }

    @Override
    public List<LoanReminderCandidate> findOverdue(LocalDate dueBefore, int maxReminders, LocalDate lastSentOnOrBefore,
                                                   UUID afterId, int limit) {
        List<BorrowingJpaEntity> rows = jpa.findOverdue(dueBefore, afterId, LoanReminderKind.OVERDUE, maxReminders,
                lastSentOnOrBefore, PageRequest.of(0, limit));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(BorrowingJpaEntity::getId).toList();
        Map<String, Integer> sent = new HashMap<>();
        for (LoanReminderJpaEntity r : jpa.findByBorrowingIds(ids, LoanReminderKind.OVERDUE)) {
            sent.merge(key(r.getBorrowingId(), r.getDueDate()), 1, Integer::sum);
        }
        List<LoanReminderCandidate> result = new ArrayList<>(rows.size());
        for (BorrowingJpaEntity b : rows) {
            result.add(new LoanReminderCandidate(b.getId(), b.getUserId(), b.getDueDate(), openItems(b),
                    sent.getOrDefault(key(b.getId(), b.getDueDate()), 0)));
        }
        return result;
    }

    @Override
    public void saveAll(List<LoanReminderRecord> records) {
        List<LoanReminderJpaEntity> entities = new ArrayList<>(records.size());
        for (LoanReminderRecord r : records) {
            entities.add(new LoanReminderJpaEntity(UUID.randomUUID(), r.borrowingId(), r.kind(), r.dueDate(), r.seq(),
                    r.sentOn(), r.eventId()));
        }
        jpa.saveAllAndFlush(entities);
    }

    private static int openItems(BorrowingJpaEntity b) {
        return (int) b.getItems().stream().filter(i -> i.getReturnDate() == null).count();
    }

    private static String key(UUID borrowingId, LocalDate dueDate) {
        return borrowingId + "|" + dueDate;
    }
}

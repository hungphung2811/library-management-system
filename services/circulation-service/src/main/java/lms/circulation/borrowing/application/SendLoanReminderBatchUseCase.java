package lms.circulation.borrowing.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lms.circulation.borrowing.domain.LoanDueSoon;
import lms.circulation.borrowing.domain.LoanOverdueReminder;
import lms.circulation.borrowing.domain.LoanReminderIds;
import lms.circulation.borrowing.domain.LoanReminderKind;
import lms.common.kernel.DomainEvent;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xử lý MỘT lô nhắc hạn trong một transaction (ADR-0030 D11): chọn tối đa {@code batchSize} phiếu (keyset theo id), ghi
 * loan_reminders rồi phát sự kiện đồng bộ. Phát lỗi thì ném ra, transaction rollback nên không còn dòng loan_reminders
 * nào của lô và hôm sau thử lại; commit lỗi sau khi đã phát thì lần sau phát lại cùng eventId tất định (consumer khử trùng).
 * Tách khỏi {@link SendLoanRemindersUseCase} để mỗi lô là một transaction qua proxy {@code @Transactional}.
 */
public class SendLoanReminderBatchUseCase {

    /** Kết quả một lô: fetched = số phiếu được chọn (nhỏ hơn batchSize nghĩa là hết), lastId = khóa keyset kế tiếp. */
    public record BatchResult(int fetched, UUID lastId) {
    }

    private final LoanReminderPort reminders;
    private final PublishLoanReminderEventPort publisher;
    private final Clock clock;

    public SendLoanReminderBatchUseCase(LoanReminderPort reminders, PublishLoanReminderEventPort publisher, Clock clock) {
        this.reminders = reminders;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** Lô sắp đến hạn: hạn trả trong [today, today + daysBefore]. */
    @Transactional
    public BatchResult dueSoon(UUID tenantId, LocalDate today, int daysBefore, UUID afterId, int batchSize) {
        List<LoanReminderCandidate> found = reminders.findDueSoon(today, today.plusDays(daysBefore), afterId, batchSize);
        Instant now = Instant.now(clock);
        List<LoanReminderRecord> records = new ArrayList<>(found.size());
        List<DomainEvent> events = new ArrayList<>(found.size());
        for (LoanReminderCandidate c : found) {
            UUID eventId = LoanReminderIds.eventId(tenantId, c.borrowingId(), LoanReminderKind.DUE_SOON, c.dueDate(), 1);
            records.add(new LoanReminderRecord(c.borrowingId(), LoanReminderKind.DUE_SOON, c.dueDate(), 1, today, eventId));
            events.add(new LoanDueSoon(eventId, c.borrowingId(), c.readerId(), c.dueDate(),
                    (int) ChronoUnit.DAYS.between(today, c.dueDate()), c.openItemCount(), now));
        }
        return persistThenPublish(found, records, events, afterId);
    }

    /**
     * Lô quá hạn: hạn trả trước (today - graceDays), đã nhắc ít hơn maxReminders lần, lần nhắc gần nhất cách ít nhất
     * intervalDays ngày. Lần nhắc đầu là ngày đầu tiên quá hạn sau graceDays.
     */
    @Transactional
    public BatchResult overdue(UUID tenantId, LocalDate today, int graceDays, int intervalDays, int maxReminders,
                               UUID afterId, int batchSize) {
        List<LoanReminderCandidate> found = reminders.findOverdue(today.minusDays(graceDays), maxReminders,
                today.minusDays(intervalDays), afterId, batchSize);
        Instant now = Instant.now(clock);
        List<LoanReminderRecord> records = new ArrayList<>(found.size());
        List<DomainEvent> events = new ArrayList<>(found.size());
        for (LoanReminderCandidate c : found) {
            int seq = c.remindersSent() + 1;
            UUID eventId = LoanReminderIds.eventId(tenantId, c.borrowingId(), LoanReminderKind.OVERDUE, c.dueDate(), seq);
            records.add(new LoanReminderRecord(c.borrowingId(), LoanReminderKind.OVERDUE, c.dueDate(), seq, today, eventId));
            events.add(new LoanOverdueReminder(eventId, c.borrowingId(), c.readerId(), c.dueDate(),
                    ChronoUnit.DAYS.between(c.dueDate(), today), seq, maxReminders, c.openItemCount(), now));
        }
        return persistThenPublish(found, records, events, afterId);
    }

    private BatchResult persistThenPublish(List<LoanReminderCandidate> found, List<LoanReminderRecord> records,
                                           List<DomainEvent> events, UUID afterId) {
        if (found.isEmpty()) {
            return new BatchResult(0, afterId);
        }
        reminders.saveAll(records);
        events.forEach(publisher::publishAndWait);
        return new BatchResult(found.size(), found.get(found.size() - 1).borrowingId());
    }
}

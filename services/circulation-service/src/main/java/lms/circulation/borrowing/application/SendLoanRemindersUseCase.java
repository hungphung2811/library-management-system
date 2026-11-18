package lms.circulation.borrowing.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import lms.circulation.policy.application.CirculationPolicyResponse;
import lms.circulation.policy.application.GetCirculationPolicyUseCase;

/**
 * Nhắc hạn trả cho MỘT tenant (người gọi đã đặt TenantContext, ADR-0030 D11). Đọc chính sách bằng bean gốc (không qua
 * cache), rồi duyệt từng lô qua {@link SendLoanReminderBatchUseCase}. Ngày hiện tại theo múi giờ của {@link Clock}
 * (Asia/Ho_Chi_Minh). Không gửi thư: chỉ ghi sổ và phát sự kiện, support-service lo kênh gửi.
 */
public class SendLoanRemindersUseCase {

    public static final int DEFAULT_BATCH_SIZE = 500;
    private static final UUID NIL = new UUID(0L, 0L);

    /** Số phiếu đã nhắc ở lượt chạy. */
    public record Summary(int dueSoon, int overdue) {
    }

    private final GetCirculationPolicyUseCase policies;
    private final SendLoanReminderBatchUseCase batches;
    private final Clock clock;
    private final int batchSize;

    public SendLoanRemindersUseCase(GetCirculationPolicyUseCase policies, SendLoanReminderBatchUseCase batches,
                                    Clock clock, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize phải >= 1");
        }
        this.policies = policies;
        this.batches = batches;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    public Summary execute(UUID tenantId) {
        CirculationPolicyResponse policy = policies.execute();
        LocalDate today = LocalDate.now(clock);
        int dueSoon = 0;
        int overdue = 0;
        if (policy.dueReminderDaysBefore() > 0) {
            UUID after = NIL;
            SendLoanReminderBatchUseCase.BatchResult r;
            do {
                r = batches.dueSoon(tenantId, today, policy.dueReminderDaysBefore(), after, batchSize);
                dueSoon += r.fetched();
                after = r.lastId();
            } while (r.fetched() == batchSize);
        }
        if (policy.overdueReminderMax() > 0) {
            UUID after = NIL;
            SendLoanReminderBatchUseCase.BatchResult r;
            do {
                r = batches.overdue(tenantId, today, policy.graceDays(), policy.overdueReminderIntervalDays(),
                        policy.overdueReminderMax(), after, batchSize);
                overdue += r.fetched();
                after = r.lastId();
            } while (r.fetched() == batchSize);
        }
        return new Summary(dueSoon, overdue);
    }
}

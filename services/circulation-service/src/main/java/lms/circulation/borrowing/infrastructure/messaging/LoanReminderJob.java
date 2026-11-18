package lms.circulation.borrowing.infrastructure.messaging;

import java.util.List;
import java.util.UUID;
import lms.circulation.borrowing.application.SendLoanRemindersUseCase;
import lms.circulation.reservation.application.ActiveTenantsPort;
import lms.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nhắc hạn trả (ADR-0030 D11): chạy hằng ngày 07:00 giờ Việt Nam, lần lượt từng tenant ACTIVE (mỗi tenant một
 * TenantContext riêng, một tenant lỗi không dừng tenant khác). Chỉ ghi sổ loan_reminders và phát sự kiện, không gửi thư.
 * <ul>
 *   <li>Giờ chạy: {@code app.borrowing.reminder-cron} (mặc định {@code 0 0 7 * * *}; "-" là tắt), múi giờ
 *       {@code app.borrowing.reminder-zone} (mặc định Asia/Ho_Chi_Minh).</li>
 *   <li>Tắt hẳn bean: {@code app.borrowing.reminder-enabled=false}.</li>
 * </ul>
 * Chạy trùng giữa nhiều instance vẫn an toàn: UNIQUE của loan_reminders chặn ghi đôi (lô thua cuộc rollback) và
 * eventId tất định giúp consumer khử trùng.
 */
@Component
@ConditionalOnProperty(name = "app.borrowing.reminder-enabled", havingValue = "true", matchIfMissing = true)
public class LoanReminderJob {

    private static final Logger log = LoggerFactory.getLogger(LoanReminderJob.class);

    private final SendLoanRemindersUseCase reminders;
    private final ObjectProvider<ActiveTenantsPort> activeTenants;

    public LoanReminderJob(SendLoanRemindersUseCase reminders, ObjectProvider<ActiveTenantsPort> activeTenants) {
        this.reminders = reminders;
        this.activeTenants = activeTenants;
    }

    @Scheduled(cron = "${app.borrowing.reminder-cron:0 0 7 * * *}",
            zone = "${app.borrowing.reminder-zone:Asia/Ho_Chi_Minh}")
    public void run() {
        ActiveTenantsPort port = activeTenants.getIfAvailable();
        if (port == null) {
            log.warn("Nhắc hạn: ActiveTenantsPort chưa được cấu hình, bỏ qua lượt chạy");
            return;
        }
        List<UUID> tenantIds = port.activeTenantIds();
        if (tenantIds.isEmpty()) {
            log.info("Nhắc hạn: không có tenant ACTIVE, bỏ qua");
            return;
        }
        for (UUID tenantId : tenantIds) {
            try {
                TenantContext.runAs(tenantId, () -> {
                    SendLoanRemindersUseCase.Summary s = reminders.execute(tenantId);
                    log.info("Nhắc hạn: tenant {} nhắc sắp hạn {} phiếu, quá hạn {} phiếu", tenantId, s.dueSoon(),
                            s.overdue());
                });
            } catch (RuntimeException e) {
                log.error("Nhắc hạn: lỗi khi xử lý tenant {}, tiếp tục tenant khác (lô lỗi đã rollback, mai chạy lại)",
                        tenantId, e);
            }
        }
    }
}

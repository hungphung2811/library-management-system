package lms.support.notification.infrastructure.scheduling;

import java.time.Duration;
import lms.support.notification.application.DispatchNotificationsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Xả hàng chờ thông báo mỗi 5 giây và dọn lượt gửi cũ mỗi ngày (ADR-0030 D8). Tắt bằng
 * lms.notification.dispatch.enabled=false. Mỗi lượt bắt mọi lỗi để job không bao giờ dừng.
 */
@Component
@ConditionalOnProperty(prefix = "lms.notification.dispatch", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class NotificationDispatchJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchJob.class);

    private final DispatchNotificationsUseCase dispatch;
    private final Duration retention;

    public NotificationDispatchJob(DispatchNotificationsUseCase dispatch,
                                   @Value("${lms.notification.retention-days:180}") int retentionDays) {
        this.dispatch = dispatch;
        this.retention = Duration.ofDays(retentionDays);
    }

    @Scheduled(fixedDelayString = "${lms.notification.dispatch.interval-ms:5000}",
            initialDelayString = "${lms.notification.dispatch.initial-delay-ms:10000}")
    public void run() {
        try {
            int sent = dispatch.dispatchAll();
            if (sent > 0) {
                log.info("Đã gửi {} thông báo", sent);
            }
        } catch (RuntimeException e) {
            log.error("Dispatch thông báo lỗi: {}", e.toString());
        }
    }

    @Scheduled(cron = "${lms.notification.retention.cron:0 30 3 * * *}", zone = "Asia/Ho_Chi_Minh")
    public void purge() {
        try {
            int deleted = dispatch.purgeAll(retention);
            log.info("Đã dọn {} lượt gửi thông báo cũ", deleted);
        } catch (RuntimeException e) {
            log.error("Dọn lượt gửi thông báo lỗi: {}", e.toString());
        }
    }
}

package lms.identity.auth.infrastructure.scheduling;

import lms.identity.auth.application.PurgeExpiredRefreshTokensUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Job hằng ngày xóa refresh token đã hết hạn quá 1 ngày (ADR-0017 mục 2.6). Chạy song song nhiều instance vô hại
 * (DELETE idempotent). Lỗi chỉ log, lần sau chạy lại. {@code @EnableScheduling} đã bật ở ContractBeanConfiguration.
 */
public class RefreshTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

    private final PurgeExpiredRefreshTokensUseCase purge;

    public RefreshTokenCleanupJob(PurgeExpiredRefreshTokensUseCase purge) {
        this.purge = purge;
    }

    @Scheduled(cron = "${lms.auth.refresh-cleanup.cron:0 30 3 * * *}",
            zone = "${lms.contract.zone:Asia/Ho_Chi_Minh}")
    public void run() {
        try {
            int deleted = purge.execute();
            log.info("Dọn refresh token hết hạn: đã xóa {} dòng", deleted);
        } catch (RuntimeException e) {
            log.warn("Dọn refresh token hết hạn thất bại, sẽ thử lại ở lần chạy sau", e);
        }
    }
}

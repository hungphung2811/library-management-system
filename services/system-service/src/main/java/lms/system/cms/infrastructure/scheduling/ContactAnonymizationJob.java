package lms.system.cms.infrastructure.scheduling;

import lms.system.cms.application.AnonymizeExpiredContactRequestsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Job hằng ngày ẩn danh yêu cầu liên hệ quá hạn lưu giữ (ADR-0011 mục 2.10). Lặp từng lô (mỗi lô một transaction) tới
 * khi hết. Idempotent và an toàn khi nhiều instance chạy chồng: xung đột khóa lạc quan chỉ làm dừng lần chạy này, lần
 * sau chạy tiếp. Log không chứa dữ liệu cá nhân.
 */
public class ContactAnonymizationJob {

    private static final Logger log = LoggerFactory.getLogger("platform-audit");
    /** Chặn vòng lặp vô hạn nếu repository có lỗi: tối đa 1000 lô mỗi lần chạy. */
    private static final int MAX_BATCHES = 1000;

    private final AnonymizeExpiredContactRequestsUseCase useCase;

    public ContactAnonymizationJob(AnonymizeExpiredContactRequestsUseCase useCase) {
        this.useCase = useCase;
    }

    @Scheduled(cron = "${lms.system.cms.anonymization.cron:0 30 3 * * *}")
    public void scheduledRun() {
        runOnce();
    }

    /** Chạy một lượt; trả tổng số yêu cầu đã ẩn danh. */
    public int runOnce() {
        int total = 0;
        try {
            for (int i = 0; i < MAX_BATCHES; i++) {
                int count = useCase.anonymizeBatch();
                total += count;
                if (count < useCase.batchSize()) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            // Không log message của ngoại lệ (có thể chứa giá trị cột); lần chạy sau thử lại
            log.warn("platform-audit operation=contact-requests-anonymized status=failed count={} error={}", total,
                    e.getClass().getSimpleName());
            return total;
        }
        if (total > 0) {
            log.info("platform-audit operation=contact-requests-anonymized count={}", total);
        }
        return total;
    }
}

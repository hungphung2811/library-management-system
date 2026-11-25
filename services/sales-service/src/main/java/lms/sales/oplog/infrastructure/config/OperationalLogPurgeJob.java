package lms.sales.oplog.infrastructure.config;

import lms.sales.oplog.application.PurgeOperationalLogsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Job hằng ngày xóa log vận hành quá {@code lms.sales.logs.retention-days} ngày (mặc định 30), theo lô. Chạy toàn bảng
 * (không theo tenant) nên việc xóa ở phạm vi ALL do adapter lưu trữ đảm nhiệm. Bật/tắt bằng
 * {@code lms.sales.logs.purge.enabled}, lịch bằng {@code lms.sales.logs.purge.cron}. Idempotent.
 */
public class OperationalLogPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(OperationalLogPurgeJob.class);

    private final PurgeOperationalLogsUseCase useCase;

    public OperationalLogPurgeJob(PurgeOperationalLogsUseCase useCase) {
        this.useCase = useCase;
    }

    @Scheduled(cron = "${lms.sales.logs.purge.cron:0 15 3 * * *}")
    public void scheduled() {
        runOnce();
    }

    /** Chạy một lượt; trả số dòng đã xóa (0 khi lỗi, chỉ log tên lớp lỗi). */
    public int runOnce() {
        try {
            int n = useCase.execute();
            log.info("Dọn log vận hành quá hạn: {} dòng", n);
            return n;
        } catch (RuntimeException e) {
            log.warn("Dọn log vận hành lỗi: {}", e.getClass().getSimpleName());
            return 0;
        }
    }
}

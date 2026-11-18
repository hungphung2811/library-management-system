package lms.identity.tenantdata.infrastructure.scheduling;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lms.identity.tenantdata.application.MaintainTenantDataExportsUseCase;
import lms.identity.tenantdata.application.RunTenantDataExportUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Job nền xuất dữ liệu tenant (ADR-0031 D3). Chỉ điều phối: nhận id PENDING cũ nhất rồi giao cho executor riêng (1 luồng,
 * hàng đợi 1, không dùng luồng lập lịch chung) nên không chặn request lẫn các job khác; việc xuất nằm ở
 * {@link RunTenantDataExportUseCase}. Mỗi chu kỳ cũng dọn gói hết hạn và đánh dấu RUNNING bị kẹt. Sau khởi động lại,
 * RUNNING còn sót được đưa về PENDING (identity chạy một instance).
 */
public class TenantExportJob {

    private static final Logger log = LoggerFactory.getLogger(TenantExportJob.class);

    private final RunTenantDataExportUseCase run;
    private final MaintainTenantDataExportsUseCase maintain;
    private final Executor executor;
    private final boolean recoverOnStart;

    public TenantExportJob(RunTenantDataExportUseCase run, MaintainTenantDataExportsUseCase maintain,
                           Executor executor, boolean recoverOnStart) {
        this.run = run;
        this.maintain = maintain;
        this.executor = executor;
        this.recoverOnStart = recoverOnStart;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        if (!recoverOnStart) {
            return;
        }
        try {
            maintain.recoverAfterRestart();
        } catch (RuntimeException e) {
            log.error("Khôi phục yêu cầu xuất sau khởi động lại thất bại: {}", e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${lms.tenant-data.export.poll-interval:PT10S}",
            initialDelayString = "${lms.tenant-data.export.poll-interval:PT10S}")
    public void poll() {
        try {
            maintain.failStale();
            maintain.expireDue();
            for (UUID id : run.pendingIds(1)) {
                submit(id);
            }
        } catch (RuntimeException e) {
            log.error("Job xuất dữ liệu tenant thất bại: {}", e.toString());
        }
    }

    private void submit(UUID id) {
        try {
            executor.execute(() -> {
                try {
                    run.run(id);
                } catch (RuntimeException e) {
                    log.error("Xuất dữ liệu {} thất bại: {}", id, e.toString());
                }
            });
        } catch (RejectedExecutionException busy) {
            // worker đang bận và hàng đợi đầy: chu kỳ sau thử lại
        }
    }
}

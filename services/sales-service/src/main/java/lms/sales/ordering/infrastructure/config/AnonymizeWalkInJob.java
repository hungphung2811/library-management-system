package lms.sales.ordering.infrastructure.config;

import java.util.UUID;
import lms.sales.ordering.application.AnonymizeWalkInContactsUseCase;
import lms.sales.shared.application.TenantDirectoryPort;
import lms.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Job hằng ngày ẩn danh tên/điện thoại khách vãng lai hết hạn lưu giữ (ADR-0010 mục 2.8). Duyệt từng tenant trong danh
 * bạ sales_tenants, mỗi tenant chạy trong {@code TenantContext.callAs} (RLS đúng tenant); lỗi một tenant chỉ ghi log
 * (mã tenant, không dữ liệu cá nhân) rồi sang tenant kế. Bật/tắt bằng {@code lms.sales.anonymize.enabled}, lịch bằng
 * {@code lms.sales.anonymize.cron}. Idempotent nên nhiều instance cùng chạy chỉ tốn thêm truy vấn.
 */
public class AnonymizeWalkInJob {

    private static final Logger log = LoggerFactory.getLogger(AnonymizeWalkInJob.class);

    private final TenantDirectoryPort tenants;
    private final AnonymizeWalkInContactsUseCase useCase;

    public AnonymizeWalkInJob(TenantDirectoryPort tenants, AnonymizeWalkInContactsUseCase useCase) {
        this.tenants = tenants;
        this.useCase = useCase;
    }

    @Scheduled(cron = "${lms.sales.anonymize.cron:0 30 2 * * *}")
    public void scheduled() {
        runOnce();
    }

    /** Chạy một lượt cho mọi tenant; trả tổng số đơn đã ẩn danh. */
    public int runOnce() {
        int total = 0;
        for (UUID tenantId : tenants.listAll()) {
            try {
                total += TenantContext.callAs(tenantId, () -> useCase.execute(tenantId));
            } catch (RuntimeException e) {
                log.warn("Ẩn danh khách vãng lai lỗi ở tenant {}: {}", tenantId, e.getClass().getSimpleName());
            }
        }
        log.info("Ẩn danh khách vãng lai: {} đơn", total);
        return total;
    }
}

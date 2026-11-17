package lms.sales.contact.infrastructure.config;

import java.util.UUID;
import lms.sales.contact.application.AnonymizeExpiredShopContactsUseCase;
import lms.sales.shared.application.TenantDirectoryPort;
import lms.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Job hằng ngày ẩn danh liên hệ của khách hết hạn lưu giữ (ADR-0015 mục 2.8). Dùng chung cờ và lịch với job khách
 * vãng lai ({@code lms.sales.anonymize.enabled/cron}), duyệt sales_tenants, mỗi tenant chạy trong
 * {@code TenantContext.callAs}. Lỗi một tenant chỉ log mã tenant và tên lớp ngoại lệ (không dữ liệu cá nhân).
 */
public class ShopContactAnonymizationJob {

    private static final Logger log = LoggerFactory.getLogger(ShopContactAnonymizationJob.class);

    private final TenantDirectoryPort tenants;
    private final AnonymizeExpiredShopContactsUseCase useCase;

    public ShopContactAnonymizationJob(TenantDirectoryPort tenants, AnonymizeExpiredShopContactsUseCase useCase) {
        this.tenants = tenants;
        this.useCase = useCase;
    }

    @Scheduled(cron = "${lms.sales.anonymize.cron:0 30 2 * * *}")
    public void scheduled() {
        runOnce();
    }

    /** Chạy một lượt cho mọi tenant; trả tổng số yêu cầu đã ẩn danh. */
    public int runOnce() {
        int total = 0;
        for (UUID tenantId : tenants.listAll()) {
            try {
                total += TenantContext.callAs(tenantId, () -> useCase.execute(tenantId));
            } catch (RuntimeException e) {
                log.warn("Ẩn danh liên hệ lỗi ở tenant {}: {}", tenantId, e.getClass().getSimpleName());
            }
        }
        log.info("Ẩn danh liên hệ của khách: {} yêu cầu", total);
        return total;
    }
}

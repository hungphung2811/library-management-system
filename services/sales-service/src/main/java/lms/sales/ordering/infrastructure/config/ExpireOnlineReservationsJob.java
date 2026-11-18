package lms.sales.ordering.infrastructure.config;

import java.util.UUID;
import lms.sales.ordering.application.ExpireOnlineReservationsUseCase;
import lms.sales.shared.application.TenantDirectoryPort;
import lms.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Job hủy đơn online quá hạn giữ hàng và nhả tồn. Duyệt từng tenant trong danh bạ, mỗi tenant chạy trong
 * {@code TenantContext.callAs} (RLS đúng tenant), cùng mẫu với {@link ExpirePaymentIntentsJob}; lỗi một tenant chỉ ghi
 * log. Chu kỳ {@code lms.sales.abuse.reservation-expiry-delay-ms} (mặc định 30 giây). Nhiều instance chạy cùng lúc an
 * toàn nhờ khóa dòng SKIP LOCKED ở từng đơn.
 */
public class ExpireOnlineReservationsJob {

    private static final Logger log = LoggerFactory.getLogger(ExpireOnlineReservationsJob.class);

    private final TenantDirectoryPort tenants;
    private final ExpireOnlineReservationsUseCase useCase;

    public ExpireOnlineReservationsJob(TenantDirectoryPort tenants, ExpireOnlineReservationsUseCase useCase) {
        this.tenants = tenants;
        this.useCase = useCase;
    }

    @Scheduled(fixedDelayString = "${lms.sales.abuse.reservation-expiry-delay-ms:30000}",
            initialDelayString = "${lms.sales.abuse.reservation-expiry-initial-delay-ms:45000}")
    public void scheduled() {
        runOnce();
    }

    /** Chạy một lượt cho mọi tenant; trả tổng số đơn đã hết hạn. */
    public int runOnce() {
        int total = 0;
        for (UUID tenantId : tenants.listAll()) {
            try {
                total += TenantContext.callAs(tenantId, () -> useCase.execute(tenantId));
            } catch (RuntimeException e) {
                log.warn("Dọn đơn hết hạn giữ hàng lỗi ở tenant {}: {}", tenantId, e.getClass().getSimpleName());
            }
        }
        if (total > 0) {
            log.info("Hết hạn giữ hàng {} đơn online", total);
        }
        return total;
    }
}

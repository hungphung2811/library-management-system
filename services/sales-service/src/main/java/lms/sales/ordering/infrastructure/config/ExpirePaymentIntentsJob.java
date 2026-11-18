package lms.sales.ordering.infrastructure.config;

import java.util.UUID;
import lms.sales.ordering.application.ExpirePaymentIntentsUseCase;
import lms.sales.shared.application.TenantDirectoryPort;
import lms.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Job đánh dấu EXPIRED các lệnh thanh toán QR quá hạn và nhả tồn/hủy đơn (ADR-0020). Duyệt từng tenant trong danh bạ
 * sales_tenants, mỗi tenant chạy trong {@code TenantContext.callAs} (RLS đúng tenant, cùng cách với
 * {@link AnonymizeWalkInJob}); lỗi một tenant chỉ ghi log rồi sang tenant kế. Chu kỳ bằng
 * {@code app.payments.expire-delay-ms} (mặc định 60 giây). Idempotent nên nhiều instance cùng chạy chỉ tốn
 * thêm truy vấn (xung đột phiên bản ở lệnh đã bị instance khác dọn chỉ ghi log).
 */
public class ExpirePaymentIntentsJob {

    private static final Logger log = LoggerFactory.getLogger(ExpirePaymentIntentsJob.class);

    private final TenantDirectoryPort tenants;
    private final ExpirePaymentIntentsUseCase useCase;

    public ExpirePaymentIntentsJob(TenantDirectoryPort tenants, ExpirePaymentIntentsUseCase useCase) {
        this.tenants = tenants;
        this.useCase = useCase;
    }

    @Scheduled(fixedDelayString = "${app.payments.expire-delay-ms:60000}",
            initialDelayString = "${app.payments.expire-initial-delay-ms:30000}")
    public void scheduled() {
        runOnce();
    }

    /** Chạy một lượt cho mọi tenant; trả tổng số lệnh đã hết hạn. */
    public int runOnce() {
        int total = 0;
        for (UUID tenantId : tenants.listAll()) {
            try {
                total += TenantContext.callAs(tenantId, () -> useCase.execute(tenantId));
            } catch (RuntimeException e) {
                log.warn("Dọn lệnh thanh toán hết hạn lỗi ở tenant {}: {}", tenantId, e.getClass().getSimpleName());
            }
        }
        if (total > 0) {
            log.info("Hết hạn {} lệnh thanh toán QR", total);
        }
        return total;
    }
}

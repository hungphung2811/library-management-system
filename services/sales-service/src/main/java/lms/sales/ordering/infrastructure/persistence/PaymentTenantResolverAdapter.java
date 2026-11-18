package lms.sales.ordering.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import lms.sales.ordering.application.PaymentTenantResolverPort;
import lms.tenant.TenantContext;
import org.springframework.stereotype.Component;

/**
 * Tra tenant của lệnh thanh toán theo mã tham chiếu cho webhook (không có JWT/tenant). Dùng phạm vi đọc tất cả tenant
 * (ADR-0008: Hibernate bỏ bộ lọc @TenantId khi đọc, policy platform_read_all của RLS cho phép SELECT) và CHỈ lấy
 * tenant_id, không trả dữ liệu nào khác. Phạm vi phải được đặt TRƯỚC khi transaction mở (kết nối lấy ra theo
 * transaction mới mang biến phiên), nên lớp này không transactional và gọi truy vấn qua bean riêng; phạm vi cũ được khôi
 * phục sau đó. Người gọi bắt buộc đã xác thực chữ ký trước khi gọi (ADR-0020).
 */
@Component
class PaymentTenantResolverAdapter implements PaymentTenantResolverPort {

    private final PaymentTenantQuery query;

    PaymentTenantResolverAdapter(PaymentTenantQuery query) {
        this.query = query;
    }

    @Override
    public Optional<UUID> findTenantIdByReference(String reference) {
        UUID previous = TenantContext.current().orElse(null);
        boolean previousAll = TenantContext.isAllTenants();
        TenantContext.bindAllTenants();
        try {
            return query.tenantOf(reference);
        } finally {
            if (previousAll) {
                TenantContext.bindAllTenants();
            } else {
                TenantContext.set(previous);
            }
        }
    }
}

package lms.identity.tenant.application;

import java.util.List;
import java.util.UUID;

/**
 * Kết quả chọn tenant: tenantId đã chọn (null chỉ khi platform admin không có tenant nào), mọi tenant người dùng
 * truy cập được (membership ACTIVE ở tenant ACTIVE) xếp theo thời điểm tham gia, và phòng ban của người dùng trong
 * tenant đã chọn (tenant_memberships.department_id; null nếu chưa gán hoặc không có tenant).
 */
public record TenantChoice(UUID tenantId, List<TenantSummary> available, UUID departmentId) {

    public TenantChoice {
        available = List.copyOf(available);
    }

    /** Lựa chọn không kèm phòng ban. */
    public TenantChoice(UUID tenantId, List<TenantSummary> available) {
        this(tenantId, available, null);
    }
}

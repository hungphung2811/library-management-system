package lms.identity.tenant.application;

import java.util.List;
import java.util.UUID;

/**
 * Kết quả chọn tenant: tenantId đã chọn (null chỉ khi platform admin không có tenant nào) và mọi tenant người dùng
 * truy cập được (membership ACTIVE ở tenant ACTIVE), xếp theo thời điểm tham gia.
 */
public record TenantChoice(UUID tenantId, List<TenantSummary> available) {

    public TenantChoice {
        available = List.copyOf(available);
    }
}

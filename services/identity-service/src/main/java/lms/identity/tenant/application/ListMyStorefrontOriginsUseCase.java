package lms.identity.tenant.application;

import java.util.UUID;
import lms.tenant.TenantContext;
import lms.identity.tenant.domain.StorefrontOriginRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant admin xem origin của CHÍNH tenant mình (ADR-0013 2.8). Bảng toàn cục không có RLS nên lọc {@code tenant_id}
 * tường minh theo {@code TenantContext.require()} (tenant từ token; endpoint không nhận id; không có tenant thì
 * {@code TenantRequiredException}).
 */
public class ListMyStorefrontOriginsUseCase {

    private final StorefrontOriginRepository origins;

    public ListMyStorefrontOriginsUseCase(StorefrontOriginRepository origins) {
        this.origins = origins;
    }

    @Transactional(readOnly = true)
    public MyStorefrontOriginsResponse execute() {
        UUID tenantId = TenantContext.require();
        return new MyStorefrontOriginsResponse(origins.findByTenant(tenantId).stream()
                .map(e -> new MyStorefrontOriginsResponse.Item(e.origin(), e.enabled())).toList());
    }
}

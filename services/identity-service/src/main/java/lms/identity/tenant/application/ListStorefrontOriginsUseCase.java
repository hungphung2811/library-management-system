package lms.identity.tenant.application;

import java.util.UUID;
import lms.identity.tenant.domain.StorefrontOriginRepository;
import lms.identity.tenant.domain.TenantNotFoundException;
import lms.identity.tenant.domain.TenantRepository;
import org.springframework.transaction.annotation.Transactional;

/** Platform admin xem origin storefront của một tenant (ADR-0013 2.8). */
public class ListStorefrontOriginsUseCase {

    private final TenantRepository tenants;
    private final StorefrontOriginRepository origins;
    private final StorefrontOriginPolicy policy;

    public ListStorefrontOriginsUseCase(TenantRepository tenants, StorefrontOriginRepository origins,
                                        StorefrontOriginPolicy policy) {
        this.tenants = tenants;
        this.origins = origins;
        this.policy = policy;
    }

    /** @throws TenantNotFoundException nếu tenant không tồn tại */
    @Transactional(readOnly = true)
    public StorefrontOriginListResponse execute(UUID tenantId) {
        tenants.findById(tenantId).orElseThrow(() -> new TenantNotFoundException("Không tìm thấy tenant " + tenantId));
        return new StorefrontOriginListResponse(policy.maxOriginsPerTenant(),
                origins.findByTenant(tenantId).stream().map(StorefrontOriginResponse::from).toList());
    }
}

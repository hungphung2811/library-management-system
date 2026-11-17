package lms.identity.tenant.application;

import java.util.List;
import java.util.UUID;
import lms.identity.tenant.domain.MembershipRepository;
import lms.identity.tenant.domain.TenantModuleRepository;
import lms.identity.tenant.domain.TenantRepository;
import org.springframework.transaction.annotation.Transactional;

/** GET /internal/me/tenants: các tenant người dùng truy cập được, theo thứ tự tham gia. */
public class ListMyTenantsUseCase {

    private final TenantRepository tenants;
    private final MembershipRepository memberships;
    private final TenantModuleRepository modules;

    public ListMyTenantsUseCase(TenantRepository tenants, MembershipRepository memberships,
                                TenantModuleRepository modules) {
        this.tenants = tenants;
        this.memberships = memberships;
        this.modules = modules;
    }

    @Transactional(readOnly = true)
    public List<TenantSummary> execute(UUID userId) {
        return AccessibleTenants.summaries(AccessibleTenants.of(userId, tenants, memberships), modules);
    }
}

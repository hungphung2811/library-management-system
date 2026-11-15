package lms.identity.tenant.application;

import java.util.List;
import java.util.UUID;
import lms.identity.tenant.domain.MembershipRepository;
import lms.identity.tenant.domain.NoTenantAccessException;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kiểm tra lại người dùng còn truy cập được tenant (membership ACTIVE, tenant ACTIVE), ví dụ khi làm mới phiên.
 * Không ghi tenant dùng gần nhất.
 */
public class VerifyTenantAccessUseCase {

    private final TenantRepository tenants;
    private final MembershipRepository memberships;

    public VerifyTenantAccessUseCase(TenantRepository tenants, MembershipRepository memberships) {
        this.tenants = tenants;
        this.memberships = memberships;
    }

    /** @throws NoTenantAccessException nếu không truy cập được tenant đó */
    @Transactional(readOnly = true)
    public TenantChoice execute(UUID userId, UUID tenantId) {
        List<Tenant> accessible = AccessibleTenants.of(userId, tenants, memberships);
        if (tenantId == null || accessible.stream().noneMatch(t -> t.id().equals(tenantId))) {
            throw new NoTenantAccessException();
        }
        return new TenantChoice(tenantId, AccessibleTenants.summaries(accessible));
    }
}

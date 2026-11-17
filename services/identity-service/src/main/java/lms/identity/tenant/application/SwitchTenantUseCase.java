package lms.identity.tenant.application;

import java.util.UUID;
import lms.identity.tenant.domain.MembershipRepository;
import lms.identity.tenant.domain.NoTenantAccessException;
import lms.identity.tenant.domain.TenantModuleRepository;
import lms.identity.tenant.domain.TenantRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đổi tenant đang dùng: kiểm tra membership ACTIVE và tenant ACTIVE rồi ghi tenant dùng gần nhất. Việc cấp cặp token
 * mới cho tenant đó do context auth làm (POST /internal/auth/switch-tenant).
 */
public class SwitchTenantUseCase {

    private final VerifyTenantAccessUseCase verify;
    private final MembershipRepository memberships;

    public SwitchTenantUseCase(TenantRepository tenants, MembershipRepository memberships,
                               TenantModuleRepository modules) {
        this.verify = new VerifyTenantAccessUseCase(tenants, memberships, modules);
        this.memberships = memberships;
    }

    /** @throws NoTenantAccessException nếu không truy cập được tenant đó */
    @Transactional
    public TenantChoice execute(UUID userId, UUID tenantId) {
        TenantChoice choice = verify.execute(userId, tenantId);
        memberships.saveLastTenant(userId, tenantId);
        return choice;
    }
}

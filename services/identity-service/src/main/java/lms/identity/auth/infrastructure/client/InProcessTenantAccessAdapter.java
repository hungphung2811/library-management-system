package lms.identity.auth.infrastructure.client;

import java.util.List;
import java.util.UUID;
import lms.identity.auth.application.TenantAccessPort;
import lms.identity.tenant.application.AddMembershipUseCase;
import lms.identity.tenant.application.ListMyTenantsUseCase;
import lms.identity.tenant.application.ResolveLoginTenantUseCase;
import lms.identity.tenant.application.SwitchTenantUseCase;
import lms.identity.tenant.application.TenantChoice;
import lms.identity.tenant.application.TenantSummary;
import lms.identity.tenant.application.VerifyTenantAccessUseCase;

/**
 * Gọi API (tầng application) của context tenant trong cùng service. Không bọc exception: NoTenantAccessException đi
 * thẳng ra handler của context tenant (HTTP 403 NO_TENANT_ACCESS).
 */
public class InProcessTenantAccessAdapter implements TenantAccessPort {

    private final ResolveLoginTenantUseCase resolve;
    private final SwitchTenantUseCase switchTenant;
    private final VerifyTenantAccessUseCase verify;
    private final ListMyTenantsUseCase listMine;
    private final AddMembershipUseCase addMembership;

    public InProcessTenantAccessAdapter(ResolveLoginTenantUseCase resolve, SwitchTenantUseCase switchTenant,
                                        VerifyTenantAccessUseCase verify, ListMyTenantsUseCase listMine,
                                        AddMembershipUseCase addMembership) {
        this.resolve = resolve;
        this.switchTenant = switchTenant;
        this.verify = verify;
        this.listMine = listMine;
        this.addMembership = addMembership;
    }

    @Override
    public TenantChoice selectForLogin(UUID userId, String tenantCodeOrNull, boolean platformAdmin) {
        return resolve.execute(userId, tenantCodeOrNull, platformAdmin);
    }

    @Override
    public TenantChoice switchTo(UUID userId, UUID tenantId) {
        return switchTenant.execute(userId, tenantId);
    }

    @Override
    public TenantChoice verify(UUID userId, UUID tenantId) {
        return verify.execute(userId, tenantId);
    }

    @Override
    public List<TenantSummary> available(UUID userId) {
        return listMine.execute(userId);
    }

    @Override
    public void addMember(UUID userId, UUID tenantId) {
        addMembership.execute(userId, tenantId);
    }
}

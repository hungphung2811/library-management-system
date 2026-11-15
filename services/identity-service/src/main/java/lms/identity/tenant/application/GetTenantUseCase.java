package lms.identity.tenant.application;

import java.util.List;
import java.util.UUID;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantNotFoundException;
import lms.identity.tenant.domain.TenantQueryRepository;
import lms.identity.tenant.domain.TenantRepository;
import lms.tenant.TenantContext;

/** Chi tiết tenant: thông tin, trường, trạng thái, số thành viên và quản trị viên (đọc role trong ngữ cảnh tenant đó). */
public class GetTenantUseCase {

    private final TenantRepository tenants;
    private final TenantQueryRepository queries;
    private final TenantRoleProvisioningPort roles;
    private final TransactionalRunner tx;

    public GetTenantUseCase(TenantRepository tenants, TenantQueryRepository queries, TenantRoleProvisioningPort roles,
                            TransactionalRunner tx) {
        this.tenants = tenants;
        this.queries = queries;
        this.roles = roles;
        this.tx = tx;
    }

    public TenantDetailResponse execute(UUID tenantId) {
        Tenant t = tenants.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException("Không tìm thấy tenant " + tenantId));
        List<UUID> admins = TenantContext.callAs(tenantId, () -> tx.inTransaction(roles::listTenantAdmins));
        long members = queries.countActiveMembers(List.of(tenantId)).getOrDefault(tenantId, 0L);
        return new TenantDetailResponse(t.id(), t.code(), t.name(), t.organizationId(), t.status(), members, admins);
    }
}

package lms.identity.tenant.application;

import java.util.List;
import java.util.UUID;
import lms.identity.tenant.domain.OrganizationRepository;
import lms.identity.tenant.domain.PlatformOperationEvent;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantNotFoundException;
import lms.identity.tenant.domain.TenantQueryRepository;
import lms.identity.tenant.domain.TenantRepository;
import lms.tenant.TenantContext;

/** Sửa tên và trường của tenant. {@code code} không đổi được sau khi tạo (tránh vỡ tham chiếu, spec §7). */
public class UpdateTenantUseCase {

    private final TenantRepository tenants;
    private final OrganizationRepository organizations;
    private final TenantQueryRepository queries;
    private final PublishPlatformEventPort events;
    private final TransactionalRunner tx;

    public UpdateTenantUseCase(TenantRepository tenants, OrganizationRepository organizations,
                               TenantQueryRepository queries, PublishPlatformEventPort events, TransactionalRunner tx) {
        this.tenants = tenants;
        this.organizations = organizations;
        this.queries = queries;
        this.events = events;
        this.tx = tx;
    }

    public TenantResponse execute(UUID tenantId, UpdateTenantRequest request, UUID actorId) {
        tenants.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException("Không tìm thấy tenant " + tenantId));
        ManageOrganizationUseCase.requireExists(organizations, request.organizationId());
        return TenantContext.callAs(tenantId, () -> {
            // đọc có khóa + sửa + ghi trong cùng transaction: không ghi đè status do thao tác đồng thời
            Tenant tenant = tx.inTransaction(() -> {
                Tenant locked = tenants.findByIdForUpdate(tenantId)
                        .orElseThrow(() -> new TenantNotFoundException("Không tìm thấy tenant " + tenantId));
                locked.rename(request.name());
                locked.moveToOrganization(request.organizationId());
                return tenants.save(locked);
            });
            events.publish(PlatformOperationEvent.of("TENANT_UPDATED", actorId, tenantId, tenantId,
                    "name=" + tenant.name() + ", organizationId=" + tenant.organizationId()));
            return TenantResponse.from(tenant, queries.countActiveMembers(List.of(tenantId)).getOrDefault(tenantId, 0L));
        });
    }
}

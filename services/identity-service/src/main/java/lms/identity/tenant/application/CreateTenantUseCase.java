package lms.identity.tenant.application;

import java.util.UUID;
import lms.identity.tenant.domain.DuplicateTenantException;
import lms.identity.tenant.domain.MembershipRepository;
import lms.identity.tenant.domain.OrganizationNotFoundException;
import lms.identity.tenant.domain.OrganizationRepository;
import lms.identity.tenant.domain.PlatformOperationEvent;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantCreatedEvent;
import lms.identity.tenant.domain.TenantRepository;
import lms.tenant.TenantContext;

/**
 * Người vận hành tạo tenant. Trong MỘT transaction (bắt đầu bên trong {@code TenantContext.callAs(tenantMới)} vì
 * roles/user_roles chịu RLS FORCE, R6): ghi tenant, sao chép role_templates thành role của tenant, tạo hoặc gắn quản trị
 * viên đầu tiên và gán role quản trị. Sau khi commit mới phát {@code TenantCreated} (circulation tạo quy tắc mượn mặc
 * định) và bản ghi audit, envelope mang tenantId của tenant mới.
 */
public class CreateTenantUseCase {

    private final TenantRepository tenants;
    private final OrganizationRepository organizations;
    private final TenantRoleProvisioningPort roles;
    private final TenantAdminAssignment admins;
    private final PublishPlatformEventPort events;
    private final TransactionalRunner tx;

    public CreateTenantUseCase(TenantRepository tenants, OrganizationRepository organizations,
                               MembershipRepository memberships, TenantRoleProvisioningPort roles,
                               TenantAccountPort accounts, PublishPlatformEventPort events, TransactionalRunner tx) {
        this.tenants = tenants;
        this.organizations = organizations;
        this.roles = roles;
        this.admins = new TenantAdminAssignment(memberships, accounts);
        this.events = events;
        this.tx = tx;
    }

    /**
     * @throws DuplicateTenantException nếu mã đã tồn tại
     * @throws OrganizationNotFoundException nếu organizationId không tồn tại
     * @throws PlatformAccountException nếu tài khoản quản trị trùng/không tồn tại
     */
    public TenantResponse execute(CreateTenantRequest request, UUID actorId) {
        TenantAdminAssignment.requireExactlyOne(request.newAdmin(), request.existingUserId());
        Tenant tenant = Tenant.create(request.code() == null ? null : request.code().trim(), request.name(),
                request.organizationId());
        if (tenants.findByCode(tenant.code()).isPresent()) {
            throw new DuplicateTenantException("Mã tenant đã tồn tại: " + tenant.code());
        }
        ManageOrganizationUseCase.requireExists(organizations, tenant.organizationId());

        return TenantContext.callAs(tenant.id(), () -> {
            UUID adminId = tx.inTransaction(() -> {
                tenants.save(tenant);
                if (roles.seedFromTemplates() == 0) {
                    throw new IllegalStateException("Chưa có role mẫu (role_templates), không tạo được tenant");
                }
                UUID admin = admins.ensureMember(tenant.id(), request.newAdmin(), request.existingUserId());
                roles.grantTenantAdmin(admin);
                return admin;
            });
            events.publish(new TenantCreatedEvent(tenant.id(), tenant.code()));
            events.publish(PlatformOperationEvent.of("TENANT_CREATED", actorId, tenant.id(), tenant.id(),
                    "code=" + tenant.code() + ", admin=" + adminId));
            return TenantResponse.from(tenant, 1);
        });
    }
}

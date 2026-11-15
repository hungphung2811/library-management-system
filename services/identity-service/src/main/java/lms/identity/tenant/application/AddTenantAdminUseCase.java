package lms.identity.tenant.application;

import java.util.UUID;
import lms.identity.tenant.domain.MembershipRepository;
import lms.identity.tenant.domain.PlatformOperationEvent;
import lms.identity.tenant.domain.TenantNotFoundException;
import lms.identity.tenant.domain.TenantRepository;
import lms.tenant.TenantContext;

/** Thêm một quản trị viên cho tenant: tài khoản mới (mật khẩu tạm) hoặc tài khoản có sẵn, rồi gán role quản trị. */
public class AddTenantAdminUseCase {

    private final TenantRepository tenants;
    private final TenantRoleProvisioningPort roles;
    private final TenantAdminAssignment admins;
    private final PublishPlatformEventPort events;
    private final TransactionalRunner tx;

    public AddTenantAdminUseCase(TenantRepository tenants, MembershipRepository memberships,
                                 TenantRoleProvisioningPort roles, TenantAccountPort accounts,
                                 PublishPlatformEventPort events, TransactionalRunner tx) {
        this.tenants = tenants;
        this.roles = roles;
        this.admins = new TenantAdminAssignment(memberships, accounts);
        this.events = events;
        this.tx = tx;
    }

    /** Trả userId của quản trị viên. */
    public UUID execute(UUID tenantId, TenantAdminRequest request, UUID actorId) {
        TenantAdminAssignment.requireExactlyOne(request.newAdmin(), request.existingUserId());
        if (tenants.findById(tenantId).isEmpty()) {
            throw new TenantNotFoundException("Không tìm thấy tenant " + tenantId);
        }
        return TenantContext.callAs(tenantId, () -> {
            UUID adminId = tx.inTransaction(() -> {
                UUID id = admins.ensureMember(tenantId, request.newAdmin(), request.existingUserId());
                roles.grantTenantAdmin(id);
                return id;
            });
            events.publish(PlatformOperationEvent.of("TENANT_ADMIN_ADDED", actorId, tenantId, adminId,
                    request.newAdmin() != null ? "newAccount" : "existingAccount"));
            return adminId;
        });
    }
}

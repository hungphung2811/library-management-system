package lms.identity.tenant.application;

import java.util.UUID;
import lms.identity.tenant.domain.PlatformOperationEvent;
import lms.identity.tenant.domain.TenantNotFoundException;
import lms.identity.tenant.domain.TenantRepository;
import lms.tenant.TenantContext;

/**
 * Gỡ quản trị viên tenant: thu hồi mọi role quản trị của tenant đó khỏi người dùng (membership giữ nguyên). Quyền mới
 * có hiệu lực ở lần đăng nhập/làm mới token kế tiếp (sự kiện RoleRevoked khiến auth thu hồi phiên).
 */
public class RemoveTenantAdminUseCase {

    private final TenantRepository tenants;
    private final TenantRoleProvisioningPort roles;
    private final PublishPlatformEventPort events;
    private final TransactionalRunner tx;

    public RemoveTenantAdminUseCase(TenantRepository tenants, TenantRoleProvisioningPort roles,
                                    PublishPlatformEventPort events, TransactionalRunner tx) {
        this.tenants = tenants;
        this.roles = roles;
        this.events = events;
        this.tx = tx;
    }

    public void execute(UUID tenantId, UUID userId, UUID actorId) {
        if (tenants.findById(tenantId).isEmpty()) {
            throw new TenantNotFoundException("Không tìm thấy tenant " + tenantId);
        }
        TenantContext.runAs(tenantId, () -> {
            boolean revoked = tx.inTransaction(() -> roles.revokeTenantAdmin(userId));
            if (!revoked) {
                throw new PlatformAccountException(PlatformAccountException.Reason.NOT_FOUND,
                        "Người dùng không phải quản trị viên của tenant");
            }
            events.publish(PlatformOperationEvent.of("TENANT_ADMIN_REMOVED", actorId, tenantId, userId, null));
        });
    }
}

package lms.identity.tenant.application;

import java.util.UUID;
import lms.identity.tenant.domain.MembershipRepository;
import lms.identity.tenant.domain.PlatformOperationEvent;
import lms.identity.tenant.domain.TenantNotFoundException;
import lms.identity.tenant.domain.TenantRepository;
import lms.tenant.TenantContext;

/**
 * Gắn tài khoản có sẵn vào tenant (membership ACTIVE, không role). Đây là đường duy nhất để một tài khoản thuộc nhiều
 * tenant (spec §5.6); quản trị viên tenant sau đó tự gán role trong tenant của mình.
 */
public class AttachUserToTenantUseCase {

    private final TenantRepository tenants;
    private final TenantAdminAssignment members;
    private final PublishPlatformEventPort events;
    private final TransactionalRunner tx;

    public AttachUserToTenantUseCase(TenantRepository tenants, MembershipRepository memberships,
                                     TenantAccountPort accounts, PublishPlatformEventPort events,
                                     TransactionalRunner tx) {
        this.tenants = tenants;
        this.members = new TenantAdminAssignment(memberships, accounts);
        this.events = events;
        this.tx = tx;
    }

    public void execute(UUID tenantId, AttachUserRequest request, UUID actorId) {
        if (tenants.findById(tenantId).isEmpty()) {
            throw new TenantNotFoundException("Không tìm thấy tenant " + tenantId);
        }
        TenantContext.runAs(tenantId, () -> {
            tx.inTransaction(() -> {
                members.attachExisting(tenantId, request.userId());
                return null;
            });
            events.publish(PlatformOperationEvent.of("MEMBER_ATTACHED", actorId, tenantId, request.userId(), null));
        });
    }
}

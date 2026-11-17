package lms.identity.accesscontrol.infrastructure.client;

import lms.identity.accesscontrol.application.GetUserAccessUseCase;
import lms.identity.user.application.TargetAdminLookupPort;
import java.util.UUID;

/**
 * Cổng tra "người dùng có role quản trị trong tenant hiện tại" (port của context user), cài đặt ở phía access-control.
 * Dùng role hiệu lực của {@link GetUserAccessUseCase} (cờ is_admin của role, không theo tên role), theo TenantContext.
 */
public class InProcessTargetAdminLookupAdapter implements TargetAdminLookupPort {

    private final GetUserAccessUseCase getUserAccess;

    public InProcessTargetAdminLookupAdapter(GetUserAccessUseCase getUserAccess) {
        this.getUserAccess = getUserAccess;
    }

    @Override
    public boolean isTenantAdmin(UUID userId) {
        return getUserAccess.execute(userId).admin();
    }
}

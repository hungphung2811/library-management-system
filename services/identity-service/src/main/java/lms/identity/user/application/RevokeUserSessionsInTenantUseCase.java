package lms.identity.user.application;

import lms.identity.user.domain.UserRepository;
import java.util.UUID;

/**
 * Quản trị viên tenant thu hồi mọi phiên (refresh token) của một người dùng TRONG TENANT HIỆN TẠI; phiên ở tenant khác
 * và phiên nền tảng không bị đụng nên không cần quy tắc "tài khoản chỉ thuộc một tenant" (G6). Người không thuộc tenant:
 * 404. Từ ADR-0017 áp thêm G3 (đích là admin tenant: chỉ admin gọi được) và G4 (đích là platform admin: chỉ platform
 * admin gọi được), xem {@link SessionManagementGuard}. Phát audit {@code UserSessionsRevokedEvent}.
 *
 * <p>Giới hạn: access token đã phát vẫn dùng được đến khi hết hạn (TTL mặc định 15 phút, {@code auth.jwt.access-token-ttl});
 * sau đó người dùng phải đăng nhập lại. Làm mới (refresh) bị từ chối ngay. Người dùng bị vô hiệu hóa (DISABLED) cũng
 * không làm mới được vì TokenService không cấp token cho tài khoản bị vô hiệu hóa.
 */
public class RevokeUserSessionsInTenantUseCase {

    private final AccountCredentialPort credentials;
    private final SessionManagementGuard guard;

    public RevokeUserSessionsInTenantUseCase(UserRepository repository, AccountCredentialPort credentials,
                                             TargetAdminLookupPort adminLookup) {
        this.credentials = credentials;
        this.guard = new SessionManagementGuard(repository, credentials, adminLookup);
    }

    /** @return số refresh token đã thu hồi */
    public int execute(SessionCaller caller, UUID userId) {
        UUID tenantId = guard.require(caller, userId);
        // Tự thu hồi phiên của mình: không phát audit (ADR-0017 G5, 2.9.4), actor null
        UUID auditActor = userId.equals(caller.userId()) ? null : caller.userId();
        return credentials.revokeSessionsInTenant(userId, tenantId, auditActor);
    }
}

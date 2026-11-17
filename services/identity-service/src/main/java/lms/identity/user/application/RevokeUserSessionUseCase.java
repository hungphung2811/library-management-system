package lms.identity.user.application;

import lms.identity.user.domain.UserRepository;
import lms.identity.user.domain.UserSessionNotFoundException;
import java.util.UUID;

/**
 * Quản trị viên thu hồi MỘT phiên của người dùng trong tenant hiện tại (quy tắc G1-G6). Phiên không có, của người khác,
 * ở tenant khác, phiên nền tảng hoặc đã kết thúc: một mã chung SESSION_NOT_FOUND. Phát audit
 * {@code UserSessionsRevokedEvent} khi thu hồi thành công. Chỉ chặn refresh: access token đã phát còn dùng được đến
 * hết TTL (mặc định 15 phút).
 */
public class RevokeUserSessionUseCase {

    private final SessionManagementGuard guard;
    private final AccountCredentialPort credentials;

    public RevokeUserSessionUseCase(UserRepository repository, AccountCredentialPort credentials,
                                    TargetAdminLookupPort adminLookup) {
        this.guard = new SessionManagementGuard(repository, credentials, adminLookup);
        this.credentials = credentials;
    }

    public void execute(SessionCaller caller, UUID userId, UUID sessionId) {
        UUID tenantId = guard.require(caller, userId);
        // Tự thu hồi phiên của mình: không phát audit (ADR-0017 G5, 2.9.4), actor null
        UUID auditActor = userId.equals(caller.userId()) ? null : caller.userId();
        if (!credentials.revokeSessionInTenant(userId, tenantId, sessionId, auditActor)) {
            throw new UserSessionNotFoundException();
        }
    }
}

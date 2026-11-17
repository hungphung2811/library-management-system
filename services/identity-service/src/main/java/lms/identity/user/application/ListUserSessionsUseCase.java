package lms.identity.user.application;

import lms.identity.user.domain.UserRepository;
import java.util.List;
import java.util.UUID;

/**
 * Quản trị viên xem phiên đang đăng nhập của một người dùng TRONG TENANT HIỆN TẠI (quy tắc G1-G6 ở
 * {@link SessionManagementGuard}). Phiên nền tảng và phiên ở tenant khác không bao giờ xuất hiện. Xem phiên của NGƯỜI
 * KHÁC phát sự kiện audit {@code UserSessionsViewedEvent} (không chứa IP/thiết bị); tự xem phiên của mình thì không.
 * Không bao bọc bằng transaction ngoài: mỗi bước (kiểm quyền, đọc phiên) tự có transaction ngắn.
 */
public class ListUserSessionsUseCase {

    private final SessionManagementGuard guard;
    private final AccountCredentialPort credentials;

    public ListUserSessionsUseCase(UserRepository repository, AccountCredentialPort credentials,
                                   TargetAdminLookupPort adminLookup) {
        this.guard = new SessionManagementGuard(repository, credentials, adminLookup);
        this.credentials = credentials;
    }

    public List<UserSessionView> execute(SessionCaller caller, UUID userId) {
        UUID tenantId = guard.require(caller, userId);
        UUID auditActor = userId.equals(caller.userId()) ? null : caller.userId();
        return credentials.sessionsInTenant(userId, tenantId, auditActor).stream()
                .map(s -> new UserSessionView(s.sessionId(), s.channel(), s.deviceSummary(), s.ipAddress(),
                        s.startedAt(), s.lastUsedAt(), s.expiresAt()))
                .toList();
    }
}

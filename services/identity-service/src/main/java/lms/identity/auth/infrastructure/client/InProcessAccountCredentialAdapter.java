package lms.identity.auth.infrastructure.client;

import lms.identity.auth.application.GetCredentialInfoUseCase;
import lms.identity.auth.application.GetCredentialInfoUseCase.CredentialInfo;
import lms.identity.auth.application.ListTenantUserSessionsUseCase;
import lms.identity.auth.application.RevokeTenantUserSessionUseCase;
import lms.identity.auth.domain.SessionNotFoundException;
import lms.identity.auth.application.RegisterCredentialUseCase;
import lms.identity.auth.application.ResetCredentialPasswordUseCase;
import lms.identity.auth.application.RevokeTenantSessionsUseCase;
import lms.identity.auth.application.UnlockCredentialUseCase;
import lms.identity.user.application.AccountCredentialPort;
import java.util.Optional;
import java.util.UUID;

/**
 * Hiện thực cổng {@link AccountCredentialPort} của context user bằng tầng application của auth (đảo phụ thuộc: user
 * không biết auth, auth đã được phép dùng tầng application của user).
 */
public class InProcessAccountCredentialAdapter implements AccountCredentialPort {

    private final RegisterCredentialUseCase register;
    private final GetCredentialInfoUseCase info;
    private final ResetCredentialPasswordUseCase resetPassword;
    private final UnlockCredentialUseCase unlock;
    private final RevokeTenantSessionsUseCase revokeSessions;
    private final ListTenantUserSessionsUseCase listSessions;
    private final RevokeTenantUserSessionUseCase revokeSession;

    public InProcessAccountCredentialAdapter(RegisterCredentialUseCase register, GetCredentialInfoUseCase info,
                                             ResetCredentialPasswordUseCase resetPassword,
                                             UnlockCredentialUseCase unlock,
                                             RevokeTenantSessionsUseCase revokeSessions,
                                             ListTenantUserSessionsUseCase listSessions,
                                             RevokeTenantUserSessionUseCase revokeSession) {
        this.listSessions = listSessions;
        this.revokeSession = revokeSession;
        this.unlock = unlock;
        this.revokeSessions = revokeSessions;
        this.register = register;
        this.info = info;
        this.resetPassword = resetPassword;
    }

    @Override
    public Optional<UUID> findUserIdByUsername(String username) {
        return info.byUsername(username).map(CredentialInfo::userId);
    }

    @Override
    public java.util.Set<String> existingUsernames(java.util.Collection<String> usernames) {
        return info.existingUsernames(usernames);
    }

    @Override
    public boolean exists(UUID userId) {
        return info.byUserId(userId).isPresent();
    }

    @Override
    public boolean isPlatformAdmin(UUID userId) {
        return info.byUserId(userId).map(CredentialInfo::platformAdmin).orElse(false);
    }

    @Override
    public void register(UUID userId, String username, String rawPassword, boolean mustChangePassword) {
        // tenantId null: membership (kèm phòng ban) do context user ghi trong cùng transaction
        register.execute(userId, username, rawPassword, null, mustChangePassword, null);
    }

    @Override
    public boolean resetToTemporaryPassword(UUID userId, String temporaryPassword) {
        return resetPassword.execute(userId, temporaryPassword);
    }

    @Override
    public boolean unlock(UUID userId, UUID actorId) {
        return unlock.execute(userId, actorId);
    }

    @Override
    public java.util.Map<UUID, java.time.Instant> activeLocks(java.util.Collection<UUID> userIds,
                                                               java.time.Instant now) {
        return info.activeLocks(userIds, now);
    }

    @Override
    public int revokeSessionsInTenant(UUID userId, UUID tenantId) {
        return revokeSessions.execute(userId, tenantId);
    }

    @Override
    public int revokeSessionsInTenant(UUID userId, UUID tenantId, UUID actorId) {
        return revokeSessions.execute(userId, tenantId, actorId);
    }

    @Override
    public java.util.List<AccountSession> sessionsInTenant(UUID userId, UUID tenantId, UUID auditActorOrNull) {
        return listSessions.execute(userId, tenantId, auditActorOrNull).stream()
                .map(s -> new AccountSession(s.sessionId(), s.channel(), s.deviceSummary(), s.ipAddress(),
                        s.startedAt(), s.lastUsedAt(), s.expiresAt()))
                .toList();
    }

    @Override
    public boolean revokeSessionInTenant(UUID userId, UUID tenantId, UUID sessionId, UUID actorId) {
        try {
            revokeSession.execute(userId, tenantId, sessionId, actorId);
            return true;
        } catch (SessionNotFoundException e) {
            return false;
        }
    }
}

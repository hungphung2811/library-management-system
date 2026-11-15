package lms.identity.auth.infrastructure.client;

import lms.identity.auth.application.GetCredentialInfoUseCase;
import lms.identity.auth.application.GetCredentialInfoUseCase.CredentialInfo;
import lms.identity.auth.application.RegisterCredentialUseCase;
import lms.identity.auth.application.ResetCredentialPasswordUseCase;
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

    public InProcessAccountCredentialAdapter(RegisterCredentialUseCase register, GetCredentialInfoUseCase info,
                                             ResetCredentialPasswordUseCase resetPassword) {
        this.register = register;
        this.info = info;
        this.resetPassword = resetPassword;
    }

    @Override
    public Optional<UUID> findUserIdByUsername(String username) {
        return info.byUsername(username).map(CredentialInfo::userId);
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
}

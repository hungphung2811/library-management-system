package lms.identity.auth.application;

import lms.identity.auth.domain.InvalidCredentialsException;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import lms.identity.auth.domain.WrongCurrentPasswordException;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC01 - Đổi mật khẩu. Phải đúng mật khẩu hiện tại; mật khẩu mới phải khác và đủ dài. Sau khi đổi, mọi phiên
 * (refresh token) bị thu hồi nên người dùng đăng nhập lại để nhận token mới đầy đủ quyền.
 */
public class ChangePasswordUseCase {

    private final UserCredentialRepository repository;
    private final SaveCredentialPort savePort;
    private final SaveRefreshTokenPort refreshTokens;
    private final PasswordHasherPort hasher;

    public ChangePasswordUseCase(UserCredentialRepository repository, SaveCredentialPort savePort,
                                 SaveRefreshTokenPort refreshTokens, PasswordHasherPort hasher) {
        this.repository = repository;
        this.savePort = savePort;
        this.refreshTokens = refreshTokens;
        this.hasher = hasher;
    }

    @Transactional
    public void execute(UUID userId, String currentPassword, String newPassword) {
        if (newPassword == null || newPassword.length() < RegisterCredentialUseCase.MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Mật khẩu mới phải có ít nhất "
                    + RegisterCredentialUseCase.MIN_PASSWORD_LENGTH + " ký tự");
        }
        UserCredential credential = repository.findByUserId(userId).orElseThrow(InvalidCredentialsException::new);
        if (!hasher.matches(currentPassword, credential.passwordHash())) {
            throw new WrongCurrentPasswordException();
        }
        if (hasher.matches(newPassword, credential.passwordHash())) {
            throw new IllegalArgumentException("Mật khẩu mới phải khác mật khẩu hiện tại");
        }
        credential.changePassword(hasher.hash(newPassword));
        savePort.save(credential);
        refreshTokens.revokeAllByUserId(userId);
    }
}

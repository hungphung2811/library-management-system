package lms.identity.auth.application;

import lms.identity.auth.domain.InvalidPasswordResetTokenException;
import lms.identity.auth.domain.PasswordResetToken;
import lms.identity.auth.domain.PasswordResetTokenRepository;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quên mật khẩu, bước xác nhận: token đúng (so theo hash), chưa hết hạn, chưa dùng; mật khẩu mới theo chính sách hiện
 * hành (độ dài tối thiểu như đổi mật khẩu). Thành công: đổi mật khẩu, bỏ cờ mustChangePassword, xóa khóa đăng nhập sai,
 * thu hồi mọi refresh token, vô hiệu các token đặt lại còn lại. Mọi lỗi về token dùng chung một thông điệp
 * ({@link InvalidPasswordResetTokenException}). Mật khẩu yếu bị từ chối TRƯỚC khi tiêu thụ token (người dùng thử lại được).
 */
public class ConfirmPasswordResetUseCase {

    private final PasswordResetTokenRepository tokens;
    private final SavePasswordResetTokenPort tokenSave;
    private final UserCredentialRepository credentials;
    private final SaveCredentialPort credentialSave;
    private final SaveRefreshTokenPort refreshTokens;
    private final PasswordHasherPort hasher;
    private final RefreshTokenCodecPort codec;
    private final UserDirectoryPort userDirectory;
    private final Clock clock;

    public ConfirmPasswordResetUseCase(PasswordResetTokenRepository tokens, SavePasswordResetTokenPort tokenSave,
                                       UserCredentialRepository credentials, SaveCredentialPort credentialSave,
                                       SaveRefreshTokenPort refreshTokens, PasswordHasherPort hasher,
                                       RefreshTokenCodecPort codec, UserDirectoryPort userDirectory, Clock clock) {
        this.tokens = tokens;
        this.tokenSave = tokenSave;
        this.credentials = credentials;
        this.credentialSave = credentialSave;
        this.refreshTokens = refreshTokens;
        this.hasher = hasher;
        this.codec = codec;
        this.userDirectory = userDirectory;
        this.clock = clock;
    }

    @Transactional
    public void execute(String rawToken, String newPassword) {
        if (newPassword == null || newPassword.length() < RegisterCredentialUseCase.MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Mật khẩu mới phải có ít nhất "
                    + RegisterCredentialUseCase.MIN_PASSWORD_LENGTH + " ký tự");
        }
        if (rawToken == null || rawToken.isBlank()) throw new InvalidPasswordResetTokenException();
        Instant now = clock.instant();
        PasswordResetToken token = tokens.findByTokenHash(codec.hash(rawToken.trim()))
                .orElseThrow(InvalidPasswordResetTokenException::new);
        token.ensureUsable(now);
        UserCredential credential = credentials.findByUserId(token.userId())
                .orElseThrow(InvalidPasswordResetTokenException::new);
        boolean disabledProfile = userDirectory.find(credential.userId())
                .map(UserDirectoryPort.UserInfo::disabled).orElse(false);
        if (!credential.isActive() || disabledProfile) throw new InvalidPasswordResetTokenException();
        if (!tokenSave.consume(token.id(), now)) throw new InvalidPasswordResetTokenException();

        credential.changePassword(hasher.hash(newPassword));
        credentialSave.save(credential);
        refreshTokens.revokeAllByUserId(credential.userId());
        tokenSave.invalidateOutstanding(credential.userId(), now);
    }
}

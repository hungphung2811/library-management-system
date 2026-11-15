package lms.identity.auth.application;

import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đặt lại mật khẩu bởi quản trị viên: mật khẩu tạm, buộc đổi ở lần đăng nhập sau, thu hồi mọi phiên. Quy tắc ai được
 * đặt lại cho ai (mục 5.6 của spec multi-tenant) do context user kiểm tra trước khi gọi.
 */
public class ResetCredentialPasswordUseCase {

    private final UserCredentialRepository repository;
    private final SaveCredentialPort savePort;
    private final SaveRefreshTokenPort refreshTokens;
    private final PasswordHasherPort hasher;

    public ResetCredentialPasswordUseCase(UserCredentialRepository repository, SaveCredentialPort savePort,
                                          SaveRefreshTokenPort refreshTokens, PasswordHasherPort hasher) {
        this.repository = repository;
        this.savePort = savePort;
        this.refreshTokens = refreshTokens;
        this.hasher = hasher;
    }

    /** @return false nếu người dùng chưa có credential (không có gì để đặt lại) */
    @Transactional
    public boolean execute(UUID userId, String temporaryPassword) {
        if (temporaryPassword == null || temporaryPassword.length() < RegisterCredentialUseCase.MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Mật khẩu tạm phải có ít nhất "
                    + RegisterCredentialUseCase.MIN_PASSWORD_LENGTH + " ký tự");
        }
        Optional<UserCredential> found = repository.findByUserId(userId);
        if (found.isEmpty()) return false;
        UserCredential credential = found.get();
        credential.resetToTemporaryPassword(hasher.hash(temporaryPassword));
        savePort.save(credential);
        refreshTokens.revokeAllByUserId(userId);
        return true;
    }
}

package lms.identity.auth.application;

import lms.identity.auth.application.PasswordHasherPort;
import lms.identity.auth.application.SaveCredentialPort;
import lms.identity.auth.domain.DuplicateUsernameException;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class RegisterCredentialUseCase {

    public static final int MIN_PASSWORD_LENGTH = 8;

    private final UserCredentialRepository repository;
    private final SaveCredentialPort savePort;
    private final PasswordHasherPort hasher;

    public RegisterCredentialUseCase(UserCredentialRepository repository, SaveCredentialPort savePort,
                                     PasswordHasherPort hasher) {
        this.repository = repository;
        this.savePort = savePort;
        this.hasher = hasher;
    }

    public UUID execute(UUID userId, String username, String rawPassword, UUID departmentId) {
        return execute(userId, username, rawPassword, departmentId, false);
    }

    @Transactional
    public UUID execute(UUID userId, String username, String rawPassword, UUID departmentId,
                        boolean mustChangePassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Mật khẩu phải có ít nhất " + MIN_PASSWORD_LENGTH + " ký tự");
        }
        String normalized = username == null ? "" : username.trim().toLowerCase();
        if (repository.existsByUsername(normalized) || repository.existsByUserId(userId)) {
            throw new DuplicateUsernameException(normalized);
        }
        UserCredential credential = UserCredential.register(userId, normalized, hasher.hash(rawPassword), departmentId,
                mustChangePassword);
        return savePort.save(credential).userId();
    }
}

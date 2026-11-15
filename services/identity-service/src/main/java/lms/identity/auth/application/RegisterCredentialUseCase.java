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
    private final TenantAccessPort tenantAccess;

    public RegisterCredentialUseCase(UserCredentialRepository repository, SaveCredentialPort savePort,
                                     PasswordHasherPort hasher, TenantAccessPort tenantAccess) {
        this.repository = repository;
        this.savePort = savePort;
        this.hasher = hasher;
        this.tenantAccess = tenantAccess;
    }

    public UUID execute(UUID userId, String username, String rawPassword, UUID departmentId) {
        return execute(userId, username, rawPassword, departmentId, false);
    }

    public UUID execute(UUID userId, String username, String rawPassword, UUID departmentId,
                        boolean mustChangePassword) {
        return execute(userId, username, rawPassword, departmentId, mustChangePassword, null);
    }

    /**
     * tenantId: tenant mà tài khoản mới là thành viên (tenant của người tạo, hoặc tenant mặc định cho tài khoản quản
     * trị khởi tạo). Không có membership thì tài khoản không đăng nhập được (trừ platform admin), nên null chỉ dùng
     * khi việc gắn tenant làm ở nơi khác.
     */
    @Transactional
    public UUID execute(UUID userId, String username, String rawPassword, UUID departmentId,
                        boolean mustChangePassword, UUID tenantId) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Mật khẩu phải có ít nhất " + MIN_PASSWORD_LENGTH + " ký tự");
        }
        String normalized = username == null ? "" : username.trim().toLowerCase();
        if (repository.existsByUsername(normalized) || repository.existsByUserId(userId)) {
            throw new DuplicateUsernameException(normalized);
        }
        UserCredential credential = UserCredential.register(userId, normalized, hasher.hash(rawPassword), departmentId,
                mustChangePassword);
        UUID created = savePort.save(credential).userId();
        if (tenantId != null) {
            // Phòng ban là dữ liệu của tenant: ghi vào membership (token đọc phòng ban từ đây)
            tenantAccess.addMember(created, tenantId, departmentId);
        }
        return created;
    }
}

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
    private final UserDirectoryPort userDirectory;

    public RegisterCredentialUseCase(UserCredentialRepository repository, SaveCredentialPort savePort,
                                     PasswordHasherPort hasher, TenantAccessPort tenantAccess,
                                     UserDirectoryPort userDirectory) {
        this.repository = repository;
        this.savePort = savePort;
        this.hasher = hasher;
        this.tenantAccess = tenantAccess;
        this.userDirectory = userDirectory;
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
     *
     * <p>Chống cấp credential chéo tenant: khi tenantId khác null, userId đã có hồ sơ users hoặc membership ở tenant
     * khác nhưng không có membership ở tenantId thì từ chối bằng DuplicateUsernameException với thông điệp chung
     * (không lộ việc userId tồn tại ở tenant khác). Gắn tài khoản có sẵn vào tenant là việc của người vận hành.
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
        if (tenantId != null && belongsOutside(userId, tenantId)) {
            throw DuplicateUsernameException.generic();
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

    private boolean belongsOutside(UUID userId, UUID tenantId) {
        if (tenantAccess.isMember(userId, tenantId)) return false;
        return userDirectory.find(userId).isPresent() || tenantAccess.hasAnyMembership(userId);
    }

    /**
     * Tạo tài khoản người vận hành (cờ platform_admin, không membership, không buộc đổi mật khẩu vì mật khẩu đến từ
     * cấu hình triển khai). Chỉ gọi từ bootstrap cấu hình, không có API.
     */
    @Transactional
    public UUID registerPlatformAdmin(String username, String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Mật khẩu phải có ít nhất " + MIN_PASSWORD_LENGTH + " ký tự");
        }
        String normalized = username == null ? "" : username.trim().toLowerCase();
        if (repository.existsByUsername(normalized)) throw new DuplicateUsernameException(normalized);
        UserCredential credential = UserCredential.restore(UUID.randomUUID(), normalized, hasher.hash(rawPassword),
                null, true, 0, null, false, true);
        return savePort.save(credential).userId();
    }
}

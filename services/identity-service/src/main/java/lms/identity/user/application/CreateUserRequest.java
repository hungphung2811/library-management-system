package lms.identity.user.application;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Tạo người dùng trong tenant hiện tại. password có giá trị: tạo luôn credential (mustChangePassword = mật khẩu tạm).
 * password null: chỉ tạo hồ sơ, id tùy chọn để gắn với credential đã tạo bằng POST /internal/auth/credentials (credential
 * đó phải đã là thành viên tenant hiện tại). departmentId là phòng ban trong tenant hiện tại (ghi vào membership).
 */
public record CreateUserRequest(
        UUID id,
        @NotBlank @Size(min = 3, max = 100) String username,
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Email @Size(max = 255) String email,
        UUID departmentId,
        @Size(min = AccountCredentialPort.MIN_PASSWORD_LENGTH, max = 128) String password,
        Boolean mustChangePassword) {

    /** Chỉ tạo hồ sơ (không có credential). */
    public CreateUserRequest(UUID id, String username, String fullName, String email, UUID departmentId) {
        this(id, username, fullName, email, departmentId, null, null);
    }
}

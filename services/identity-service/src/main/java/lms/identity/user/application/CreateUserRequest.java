package lms.identity.user.application;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** id tùy chọn (để gắn với credential đã có). Credential (mật khẩu) tạo riêng bằng POST /internal/auth/credentials với cùng id. */
public record CreateUserRequest(
        UUID id,
        @NotBlank @Size(min = 3, max = 100) String username,
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Email @Size(max = 255) String email,
        UUID departmentId) {
}

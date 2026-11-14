package lms.identity.user.application;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Tên đăng nhập không đổi được. departmentId null nghĩa là bỏ phòng ban. */
public record UpdateUserRequest(
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Email @Size(max = 255) String email,
        UUID departmentId) {
}

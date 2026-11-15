package lms.identity.user.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Mật khẩu tạm do quản trị viên đặt; người dùng phải đổi ở lần đăng nhập sau. */
public record ResetUserPasswordRequest(
        @NotBlank @Size(min = AccountCredentialPort.MIN_PASSWORD_LENGTH, max = 128) String temporaryPassword) {
}

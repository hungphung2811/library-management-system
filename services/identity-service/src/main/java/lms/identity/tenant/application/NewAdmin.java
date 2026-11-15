package lms.identity.tenant.application;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Tài khoản quản trị mới do người vận hành tạo; temporaryPassword là mật khẩu tạm, buộc đổi ở lần đăng nhập đầu. */
public record NewAdmin(
        @NotBlank @Size(min = 3, max = 100) String username,
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 128) String temporaryPassword) {
}

package lms.identity.auth.application;

import java.util.UUID;

/**
 * Tạo tài khoản tự đăng ký (hồ sơ + credential + membership) trong tenant hiện tại của TenantContext, do context user
 * đảm nhiệm. Ném DuplicateUsernameException (trùng ở bất kỳ tenant nào, thông điệp chung) hoặc InvalidSignupException.
 */
public interface SignupAccountPort {

    UUID register(String username, String rawPassword, String fullName, String email);
}

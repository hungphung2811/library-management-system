package lms.identity.tenant.application;

import java.util.UUID;

/**
 * Cổng sang context user/auth: tài khoản (hồ sơ users + credential) là dữ liệu toàn cục. Lỗi trùng/không hợp lệ được
 * dịch sang {@link PlatformAccountException}.
 */
public interface TenantAccountPort {

    /**
     * Tạo tài khoản mới với mật khẩu tạm (buộc đổi ở lần đăng nhập đầu) và membership vào tenant hiện tại
     * (TenantContext); trả userId.
     */
    UUID createAccount(NewAdmin account);

    /** Tài khoản có credential (đăng nhập được). */
    boolean exists(UUID userId);
}

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

    /**
     * Như {@link #createAccount(NewAdmin)} nhưng với id người dùng cho trước (null = tự sinh) và cờ buộc đổi mật khẩu
     * tùy chọn. Chỉ dùng cho dữ liệu demo (ADR-0018). Mặc định chỉ hỗ trợ cấu hình thường (id tự sinh, buộc đổi).
     */
    default UUID createAccount(NewAdmin account, UUID userId, boolean mustChangePassword) {
        if (userId != null || !mustChangePassword) {
            throw new UnsupportedOperationException("createAccount với id/mustChangePassword tùy chọn");
        }
        return createAccount(account);
    }

    /** Tài khoản có credential (đăng nhập được). */
    boolean exists(UUID userId);
}

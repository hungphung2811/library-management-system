package lms.identity.user.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Cổng sang context auth (credential: tên đăng nhập, mật khẩu, cờ platform admin). Context user không biết auth; adapter
 * nằm ở auth (auth được dùng tầng application của user). credentials là bảng toàn cục (mọi tenant).
 */
public interface AccountCredentialPort {

    /** Độ dài tối thiểu của mật khẩu (kể cả mật khẩu tạm), trùng quy tắc của context auth. */
    int MIN_PASSWORD_LENGTH = 8;

    /** userId đang giữ tên đăng nhập này (đã chuẩn hóa), rỗng nếu chưa ai dùng. */
    Optional<UUID> findUserIdByUsername(String username);

    boolean exists(UUID userId);

    /** Tài khoản có cờ platform_admin (người vận hành). Không có credential thì false. */
    boolean isPlatformAdmin(UUID userId);

    /** Tạo credential, không gắn tenant (membership do context user ghi cùng transaction). */
    void register(UUID userId, String username, String rawPassword, boolean mustChangePassword);

    /** Đặt mật khẩu tạm (buộc đổi ở lần đăng nhập sau), thu hồi mọi phiên; false nếu chưa có credential. */
    boolean resetToTemporaryPassword(UUID userId, String temporaryPassword);
}

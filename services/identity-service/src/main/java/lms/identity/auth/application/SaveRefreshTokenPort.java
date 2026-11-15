package lms.identity.auth.application;

import lms.identity.auth.domain.RefreshToken;
import java.util.UUID;

public interface SaveRefreshTokenPort {
    RefreshToken save(RefreshToken token);

    /** Thu hồi mọi refresh token còn hiệu lực của người dùng (buộc đăng nhập lại). Tự mở transaction nếu chưa có. */
    void revokeAllByUserId(UUID userId);

    /**
     * Lưu refresh token cũ đã thu hồi (nếu có) và refresh token mới trong CÙNG một giao dịch: hoặc cả hai, hoặc không
     * gì (xoay vòng phiên, đổi tenant). Adapter thật tự mở transaction; bản mặc định chỉ dành cho port giả trong test.
     */
    default void rotate(RefreshToken revokedOrNull, RefreshToken issued) {
        if (revokedOrNull != null) save(revokedOrNull);
        save(issued);
    }
}

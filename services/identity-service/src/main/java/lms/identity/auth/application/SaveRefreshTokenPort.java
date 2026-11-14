package lms.identity.auth.application;

import lms.identity.auth.domain.RefreshToken;
import java.util.UUID;

public interface SaveRefreshTokenPort {
    RefreshToken save(RefreshToken token);

    /** Thu hồi mọi refresh token còn hiệu lực của người dùng (buộc đăng nhập lại). */
    void revokeAllByUserId(UUID userId);
}

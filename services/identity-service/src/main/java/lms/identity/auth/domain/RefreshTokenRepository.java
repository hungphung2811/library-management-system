package lms.identity.auth.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Token hoạt động (chưa thu hồi, chưa hết hạn) của người dùng ở mọi tenant và kênh; mỗi phiên đúng một dòng. */
    default List<RefreshToken> findActiveByUserId(UUID userId, Instant now) {
        throw new UnsupportedOperationException("findActiveByUserId");
    }

    /** Như trên nhưng chỉ phiên thuộc tenantId (tenantId bắt buộc; phiên nền tảng không bao giờ khớp). */
    default List<RefreshToken> findActiveByUserIdAndTenantId(UUID userId, UUID tenantId, Instant now) {
        throw new UnsupportedOperationException("findActiveByUserIdAndTenantId");
    }
}

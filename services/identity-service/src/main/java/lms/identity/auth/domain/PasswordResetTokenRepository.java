package lms.identity.auth.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /** Số token đã tạo cho người dùng từ mốc since (giới hạn tần suất yêu cầu). */
    long countCreatedSince(UUID userId, Instant since);
}

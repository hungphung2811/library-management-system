package lms.identity.auth.infrastructure.persistence;

import lms.identity.auth.application.SavePasswordResetTokenPort;
import lms.identity.auth.domain.PasswordResetToken;
import lms.identity.auth.domain.PasswordResetTokenRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Persistence của token đặt lại mật khẩu (bảng toàn cục password_reset_tokens). Câu UPDATE tự mở transaction nếu cần. */
public class PasswordResetPersistenceAdapter implements PasswordResetTokenRepository, SavePasswordResetTokenPort {

    private final PasswordResetTokenJpaRepository jpa;

    public PasswordResetPersistenceAdapter(PasswordResetTokenJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<PasswordResetToken> findByTokenHash(String tokenHash) {
        return jpa.findByTokenHash(tokenHash).map(e -> PasswordResetToken.restore(e.getId(), e.getUserId(),
                e.getTokenHash(), e.getExpiresAt(), e.getCreatedAt(), e.getUsedAt()));
    }

    @Override
    public long countCreatedSince(UUID userId, Instant since) {
        return jpa.countByUserIdAndCreatedAtGreaterThanEqual(userId, since);
    }

    @Override
    @Transactional
    public PasswordResetToken save(PasswordResetToken t) {
        jpa.save(new PasswordResetTokenJpaEntity(t.id(), t.userId(), t.tokenHash(), t.expiresAt(), t.usedAt(),
                t.createdAt()));
        return t;
    }

    @Override
    @Transactional
    public void invalidateOutstanding(UUID userId, Instant now) {
        jpa.invalidateOutstanding(userId, now);
    }

    @Override
    @Transactional
    public boolean consume(UUID tokenId, Instant now) {
        return jpa.consume(tokenId, now) == 1;
    }
}

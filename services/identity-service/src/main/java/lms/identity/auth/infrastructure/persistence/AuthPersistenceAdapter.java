package lms.identity.auth.infrastructure.persistence;

import lms.identity.auth.application.SaveCredentialPort;
import lms.identity.auth.application.SaveRefreshTokenPort;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.TokenChannel;
import lms.identity.auth.domain.RefreshTokenRepository;
import lms.identity.auth.domain.UserCredentialRepository;
import lms.identity.auth.domain.RefreshTokenSupersededException;
import lms.identity.auth.domain.RevokeReason;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/** Adapter persistence cho credential và refresh token; tách Domain Model khỏi Persistence Model. */
public class AuthPersistenceAdapter implements UserCredentialRepository, SaveCredentialPort,
        RefreshTokenRepository, SaveRefreshTokenPort, lms.identity.auth.application.TenantSessionRevokerPort {

    private final CredentialJpaRepository credentials;
    private final RefreshTokenJpaRepository refreshTokens;
    private final Clock clock;

    public AuthPersistenceAdapter(CredentialJpaRepository credentials, RefreshTokenJpaRepository refreshTokens) {
        this(credentials, refreshTokens, Clock.systemUTC());
    }

    public AuthPersistenceAdapter(CredentialJpaRepository credentials, RefreshTokenJpaRepository refreshTokens,
                                  Clock clock) {
        this.credentials = credentials;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    // ---- UserCredential ----

    @Override
    public UserCredential save(UserCredential c) {
        credentials.save(new UserCredentialJpaEntity(c.userId(), c.username(), c.passwordHash(), c.departmentId(),
                c.isActive(), c.failedAttempts(), c.lockedUntil(), c.mustChangePassword(), c.platformAdmin()));
        return c;
    }

    @Override
    public Optional<UserCredential> findByUsername(String username) {
        return credentials.findByUsername(username).map(AuthPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<UserCredential> findByUserId(UUID userId) {
        return credentials.findById(userId).map(AuthPersistenceAdapter::toDomain);
    }

    @Override
    public java.util.List<UserCredential> findAllByUserIds(java.util.Collection<UUID> userIds) {
        return credentials.findAllById(userIds).stream().map(AuthPersistenceAdapter::toDomain).toList();
    }

    @Override
    public boolean existsByUserId(UUID userId) {
        return credentials.existsById(userId);
    }

    @Override
    public boolean existsByUsername(String username) {
        return credentials.existsByUsername(username);
    }

    // ---- RefreshToken ----

    @Override
    public RefreshToken save(RefreshToken t) {
        refreshTokens.save(new RefreshTokenJpaEntity(t.id(), t.userId(), t.tenantId(), t.platformSession(),
                t.tokenHash(), t.expiresAt(), t.isRevoked(), t.createdAt(), t.channel().name(), t.sessionId(),
                t.sessionStartedAt(), t.deviceSummary(), t.clientIp(), t.revokedAt(),
                t.revokedReason() == null ? null : t.revokedReason().name()));
        return t;
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return refreshTokens.findByTokenHash(tokenHash).map(AuthPersistenceAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RefreshToken> findActiveByUserId(UUID userId, Instant now) {
        return refreshTokens.findActiveByUserId(userId, now).stream().map(AuthPersistenceAdapter::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<RefreshToken> findActiveByUserIdAndTenantId(UUID userId, UUID tenantId, Instant now) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId bắt buộc");
        return refreshTokens.findActiveByUserIdAndTenantId(userId, tenantId, now).stream()
                .map(AuthPersistenceAdapter::toDomain).toList();
    }

    private static RefreshToken toDomain(RefreshTokenJpaEntity e) {
        return RefreshToken.restore(e.getId(), e.getUserId(), e.getTenantId(), e.isPlatformSession(),
                e.getTokenHash(), e.getExpiresAt(), e.isRevoked(), e.getCreatedAt(),
                TokenChannel.parse(e.getChannel()), e.getSessionId(), e.getSessionStartedAt(), e.getDeviceSummary(),
                e.getClientIp(), e.getRevokedAt(), RevokeReason.parse(e.getRevokedReason()));
    }

    /** Câu UPDATE cần transaction: tự mở (transaction ngắn) khi người gọi không ở trong transaction nào. */
    @Override
    @Transactional
    public void revokeAllByUserId(UUID userId) {
        refreshTokens.revokeAllByUserId(userId, clock.instant(), RevokeReason.BULK.name());
    }

    @Override
    @Transactional
    public int revokeAllInTenant(UUID userId, UUID tenantId) {
        return refreshTokens.revokeAllByUserIdAndTenantId(userId, tenantId, clock.instant(),
                RevokeReason.SESSION_REVOKED.name());
    }

    @Override
    @Transactional
    public int revokeSessionInTenant(UUID userId, UUID tenantId, UUID sessionId) {
        if (tenantId == null) return 0;
        return refreshTokens.revokeBySessionInTenant(userId, tenantId, sessionId, clock.instant(),
                RevokeReason.SESSION_REVOKED.name());
    }

    @Override
    @Transactional
    public int revokeSession(UUID sessionId, RevokeReason reason, Instant now) {
        return refreshTokens.revokeBySession(sessionId, now, reason.name());
    }

    @Override
    @Transactional
    public int revokeSessionOfUser(UUID userId, UUID sessionId, Instant now) {
        return refreshTokens.revokeBySessionOfUser(userId, sessionId, now, RevokeReason.SESSION_REVOKED.name());
    }

    @Override
    @Transactional
    public int revokeAllExcept(UUID userId, UUID keepSessionId, Instant now) {
        if (keepSessionId == null) {
            return refreshTokens.revokeAllByUserId(userId, now, RevokeReason.SESSION_REVOKED.name());
        }
        return refreshTokens.revokeAllExceptSession(userId, keepSessionId, now, RevokeReason.SESSION_REVOKED.name());
    }

    @Override
    @Transactional
    public int deleteExpiredBefore(Instant cutoff, int batch) {
        List<UUID> ids = refreshTokens.findExpiredIds(cutoff, PageRequest.of(0, batch));
        return ids.isEmpty() ? 0 : refreshTokens.deleteByIds(ids);
    }

    @Override
    @Transactional
    public void rotate(RefreshToken revokedOrNull, RefreshToken issued) {
        if (revokedOrNull != null) {
            Instant at = revokedOrNull.revokedAt() != null ? revokedOrNull.revokedAt() : clock.instant();
            RevokeReason reason = revokedOrNull.revokedReason() != null ? revokedOrNull.revokedReason()
                    : RevokeReason.ROTATED;
            // Cập nhật có điều kiện: request song song đã xoay trước thì 0 dòng, không chèn token mới (rollback)
            if (refreshTokens.revokeIfActive(revokedOrNull.id(), at, reason.name()) == 0) {
                throw new RefreshTokenSupersededException();
            }
        }
        save(issued);
    }

    private static UserCredential toDomain(UserCredentialJpaEntity e) {
        return UserCredential.restore(e.getUserId(), e.getUsername(), e.getPasswordHash(), e.getDepartmentId(),
                e.isActive(), e.getFailedAttempts(), e.getLockedUntil(), e.isMustChangePassword(),
                e.isPlatformAdmin());
    }
}

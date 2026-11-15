package lms.identity.auth.infrastructure.persistence;

import lms.identity.auth.application.SaveCredentialPort;
import lms.identity.auth.application.SaveRefreshTokenPort;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.RefreshTokenRepository;
import lms.identity.auth.domain.UserCredentialRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Adapter persistence cho credential và refresh token; tách Domain Model khỏi Persistence Model. */
public class AuthPersistenceAdapter implements UserCredentialRepository, SaveCredentialPort,
        RefreshTokenRepository, SaveRefreshTokenPort {

    private final CredentialJpaRepository credentials;
    private final RefreshTokenJpaRepository refreshTokens;

    public AuthPersistenceAdapter(CredentialJpaRepository credentials, RefreshTokenJpaRepository refreshTokens) {
        this.credentials = credentials;
        this.refreshTokens = refreshTokens;
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
                t.tokenHash(), t.expiresAt(),
                t.isRevoked(), t.createdAt()));
        return t;
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return refreshTokens.findByTokenHash(tokenHash).map(e -> RefreshToken.restore(e.getId(), e.getUserId(),
                e.getTenantId(), e.isPlatformSession(),
                e.getTokenHash(), e.getExpiresAt(), e.isRevoked(), e.getCreatedAt()));
    }

    /** Câu UPDATE cần transaction: tự mở (transaction ngắn) khi người gọi không ở trong transaction nào. */
    @Override
    @Transactional
    public void revokeAllByUserId(UUID userId) {
        refreshTokens.revokeAllByUserId(userId);
    }

    @Override
    @Transactional
    public void rotate(RefreshToken revokedOrNull, RefreshToken issued) {
        if (revokedOrNull != null) save(revokedOrNull);
        save(issued);
    }

    private static UserCredential toDomain(UserCredentialJpaEntity e) {
        return UserCredential.restore(e.getUserId(), e.getUsername(), e.getPasswordHash(), e.getDepartmentId(),
                e.isActive(), e.getFailedAttempts(), e.getLockedUntil(), e.isMustChangePassword(),
                e.isPlatformAdmin());
    }
}

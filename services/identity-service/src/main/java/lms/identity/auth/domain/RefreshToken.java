package lms.identity.auth.domain;

import lms.identity.auth.domain.InvalidRefreshTokenException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Refresh token dùng một lần (rotation). Chỉ lưu bản băm, không lưu token gốc. */
public class RefreshToken {

    private final UUID id;
    private final UUID userId;
    private final String tokenHash;
    private final Instant expiresAt;
    private boolean revoked;
    private final Instant createdAt;

    private RefreshToken(UUID id, UUID userId, String tokenHash, Instant expiresAt, boolean revoked, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = revoked;
        this.createdAt = createdAt;
    }

    public static RefreshToken issue(UUID userId, String tokenHash, Instant now, Duration ttl) {
        return new RefreshToken(UUID.randomUUID(), userId, tokenHash, now.plus(ttl), false, now);
    }

    public static RefreshToken restore(UUID id, UUID userId, String tokenHash, Instant expiresAt,
                                       boolean revoked, Instant createdAt) {
        return new RefreshToken(id, userId, tokenHash, expiresAt, revoked, createdAt);
    }

    public void revoke() {
        revoked = true;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public void ensureUsable(Instant now) {
        if (revoked) throw new InvalidRefreshTokenException("Refresh token đã bị thu hồi");
        if (isExpired(now)) throw new InvalidRefreshTokenException("Refresh token đã hết hạn");
    }

    public UUID id() { return id; }
    public UUID userId() { return userId; }
    public String tokenHash() { return tokenHash; }
    public Instant expiresAt() { return expiresAt; }
    public boolean isRevoked() { return revoked; }
    public Instant createdAt() { return createdAt; }
}

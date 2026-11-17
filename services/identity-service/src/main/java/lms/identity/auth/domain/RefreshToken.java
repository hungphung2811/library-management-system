package lms.identity.auth.domain;

import lms.identity.auth.domain.InvalidRefreshTokenException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Refresh token dùng một lần (rotation). Chỉ lưu bản băm, không lưu token gốc. Gắn tenant đã chọn (tenantId), hoặc
 * là phiên nền tảng không tenant của platform admin (platformSession). Token không có cả hai (phát hành trước khi có
 * đa tenant) không làm mới được.
 */
public class RefreshToken {

    private final UUID id;
    private final UUID userId;
    private final UUID tenantId;
    private final boolean platformSession;
    private final String tokenHash;
    private final Instant expiresAt;
    private boolean revoked;
    private final Instant createdAt;
    /** Kênh của phiên; refresh giữ kênh của token gốc (token phát hành trước V18 là STAFF). */
    private final TokenChannel channel;

    private RefreshToken(UUID id, UUID userId, UUID tenantId, boolean platformSession, String tokenHash,
                         Instant expiresAt, boolean revoked, Instant createdAt, TokenChannel channel) {
        this.id = id;
        this.userId = userId;
        this.tenantId = tenantId;
        this.platformSession = platformSession;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = revoked;
        this.createdAt = createdAt;
        this.channel = channel == null ? TokenChannel.STAFF : channel;
    }

    /** Token không gắn tenant, như token phát hành trước khi có đa tenant (không làm mới được). */
    public static RefreshToken issue(UUID userId, String tokenHash, Instant now, Duration ttl) {
        return new RefreshToken(UUID.randomUUID(), userId, null, false, tokenHash, now.plus(ttl), false, now,
                TokenChannel.STAFF);
    }

    public static RefreshToken issueForTenant(UUID userId, UUID tenantId, String tokenHash, Instant now, Duration ttl) {
        return issueForTenant(userId, tenantId, tokenHash, now, ttl, TokenChannel.STAFF);
    }

    public static RefreshToken issueForTenant(UUID userId, UUID tenantId, String tokenHash, Instant now, Duration ttl,
                                              TokenChannel channel) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId không được null");
        return new RefreshToken(UUID.randomUUID(), userId, tenantId, false, tokenHash, now.plus(ttl), false, now,
                channel);
    }

    /** Phiên nền tảng của platform admin không thuộc tenant nào. */
    public static RefreshToken issueForPlatform(UUID userId, String tokenHash, Instant now, Duration ttl) {
        return new RefreshToken(UUID.randomUUID(), userId, null, true, tokenHash, now.plus(ttl), false, now,
                TokenChannel.STAFF);
    }

    public static RefreshToken restore(UUID id, UUID userId, String tokenHash, Instant expiresAt,
                                       boolean revoked, Instant createdAt) {
        return restore(id, userId, null, false, tokenHash, expiresAt, revoked, createdAt);
    }

    public static RefreshToken restore(UUID id, UUID userId, UUID tenantId, boolean platformSession, String tokenHash,
                                       Instant expiresAt, boolean revoked, Instant createdAt) {
        return restore(id, userId, tenantId, platformSession, tokenHash, expiresAt, revoked, createdAt,
                TokenChannel.STAFF);
    }

    public static RefreshToken restore(UUID id, UUID userId, UUID tenantId, boolean platformSession, String tokenHash,
                                       Instant expiresAt, boolean revoked, Instant createdAt, TokenChannel channel) {
        return new RefreshToken(id, userId, tenantId, platformSession, tokenHash, expiresAt, revoked, createdAt,
                channel);
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
    public UUID tenantId() { return tenantId; }
    public boolean platformSession() { return platformSession; }
    public String tokenHash() { return tokenHash; }
    public Instant expiresAt() { return expiresAt; }
    public boolean isRevoked() { return revoked; }
    public Instant createdAt() { return createdAt; }
    public TokenChannel channel() { return channel; }
}

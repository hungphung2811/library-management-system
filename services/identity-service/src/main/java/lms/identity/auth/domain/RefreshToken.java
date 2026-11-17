package lms.identity.auth.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Refresh token dùng một lần (rotation). Chỉ lưu bản băm, không lưu token gốc. Gắn tenant đã chọn (tenantId), hoặc
 * là phiên nền tảng không tenant (platformSession). Token không có cả hai (phát hành trước khi có đa tenant) không
 * làm mới được.
 *
 * <p>Họ phiên (ADR-0017): mỗi lần đăng nhập tạo một {@code sessionId} mới; xoay vòng (refresh, đổi tenant) giữ nguyên
 * {@code sessionId} và {@code sessionStartedAt}. Mỗi phiên còn sống có đúng một token hoạt động (đầu họ), mang nhãn
 * thiết bị và IP gần nhất; khi bị thu hồi, nhãn thiết bị và IP bị xóa khỏi dòng.
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
    private final UUID sessionId;
    private final Instant sessionStartedAt;
    private String deviceSummary;
    private String clientIp;
    private Instant revokedAt;
    private RevokeReason revokedReason;

    private RefreshToken(UUID id, UUID userId, UUID tenantId, boolean platformSession, String tokenHash,
                         Instant expiresAt, boolean revoked, Instant createdAt, TokenChannel channel,
                         UUID sessionId, Instant sessionStartedAt, String deviceSummary, String clientIp,
                         Instant revokedAt, RevokeReason revokedReason) {
        this.id = id;
        this.userId = userId;
        this.tenantId = tenantId;
        this.platformSession = platformSession;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = revoked;
        this.createdAt = createdAt;
        this.channel = channel == null ? TokenChannel.STAFF : channel;
        this.sessionId = sessionId == null ? id : sessionId;
        this.sessionStartedAt = sessionStartedAt == null ? createdAt : sessionStartedAt;
        this.deviceSummary = deviceSummary;
        this.clientIp = clientIp;
        this.revokedAt = revokedAt;
        this.revokedReason = revokedReason;
    }

    private static RefreshToken fresh(UUID userId, UUID tenantId, boolean platform, String tokenHash, Instant now,
                                      Duration ttl, TokenChannel channel) {
        UUID id = UUID.randomUUID();
        // Phiên mới: sessionId riêng, độc lập với id của token
        return new RefreshToken(id, userId, tenantId, platform, tokenHash, now.plus(ttl), false, now, channel,
                UUID.randomUUID(), now, null, null, null, null);
    }

    /** Token không gắn tenant, như token phát hành trước khi có đa tenant (không làm mới được). */
    public static RefreshToken issue(UUID userId, String tokenHash, Instant now, Duration ttl) {
        return fresh(userId, null, false, tokenHash, now, ttl, TokenChannel.STAFF);
    }

    public static RefreshToken issueForTenant(UUID userId, UUID tenantId, String tokenHash, Instant now, Duration ttl) {
        return issueForTenant(userId, tenantId, tokenHash, now, ttl, TokenChannel.STAFF);
    }

    public static RefreshToken issueForTenant(UUID userId, UUID tenantId, String tokenHash, Instant now, Duration ttl,
                                              TokenChannel channel) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId không được null");
        return fresh(userId, tenantId, false, tokenHash, now, ttl, channel);
    }

    /** Phiên nền tảng của platform admin không thuộc tenant nào. */
    public static RefreshToken issueForPlatform(UUID userId, String tokenHash, Instant now, Duration ttl) {
        return fresh(userId, null, true, tokenHash, now, ttl, TokenChannel.STAFF);
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

    /** Dữ liệu cũ không có họ phiên: sessionId = id, sessionStartedAt = createdAt (như backfill của V24). */
    public static RefreshToken restore(UUID id, UUID userId, UUID tenantId, boolean platformSession, String tokenHash,
                                       Instant expiresAt, boolean revoked, Instant createdAt, TokenChannel channel) {
        return new RefreshToken(id, userId, tenantId, platformSession, tokenHash, expiresAt, revoked, createdAt,
                channel, null, null, null, null, null, null);
    }

    public static RefreshToken restore(UUID id, UUID userId, UUID tenantId, boolean platformSession, String tokenHash,
                                       Instant expiresAt, boolean revoked, Instant createdAt, TokenChannel channel,
                                       UUID sessionId, Instant sessionStartedAt, String deviceSummary,
                                       String clientIp, Instant revokedAt, RevokeReason revokedReason) {
        return new RefreshToken(id, userId, tenantId, platformSession, tokenHash, expiresAt, revoked, createdAt,
                channel, sessionId, sessionStartedAt, deviceSummary, clientIp, revokedAt, revokedReason);
    }

    /** Bản sao cùng token nhưng thuộc họ phiên đã có (xoay vòng giữ sessionId và thời điểm bắt đầu phiên). */
    public RefreshToken inSession(UUID existingSessionId, Instant existingStartedAt) {
        return new RefreshToken(id, userId, tenantId, platformSession, tokenHash, expiresAt, revoked, createdAt,
                channel, existingSessionId, existingStartedAt, deviceSummary, clientIp, revokedAt, revokedReason);
    }

    /** Bản sao có nhãn thiết bị và IP (null = không rõ). */
    public RefreshToken withDevice(String device, String ip) {
        return new RefreshToken(id, userId, tenantId, platformSession, tokenHash, expiresAt, revoked, createdAt,
                channel, sessionId, sessionStartedAt, device, ip, revokedAt, revokedReason);
    }

    /** Thu hồi không ghi lý do (giữ cho code cũ); lý do và thời điểm để trống. */
    public void revoke() {
        revoked = true;
    }

    /** Thu hồi kèm lý do và thời điểm; xóa nhãn thiết bị và IP khỏi dòng đã thu hồi (không giữ lịch sử IP). */
    public void revoke(RevokeReason reason, Instant now) {
        revoked = true;
        revokedAt = now;
        revokedReason = reason;
        deviceSummary = null;
        clientIp = null;
    }

    /**
     * Token vừa bị xoay vòng và còn trong cửa sổ ân hạn: dùng lại lúc này là race hợp lệ (nhiều tab), không phải dấu
     * hiệu bị đánh cắp. Ân hạn bằng 0 thì luôn false.
     */
    public boolean isRecentlyRotated(Instant now, Duration grace) {
        return revoked && revokedReason == RevokeReason.ROTATED && revokedAt != null
                && grace != null && !grace.isZero() && !grace.isNegative()
                && !now.isAfter(revokedAt.plus(grace));
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
    public UUID sessionId() { return sessionId; }
    public Instant sessionStartedAt() { return sessionStartedAt; }
    public String deviceSummary() { return deviceSummary; }
    public String clientIp() { return clientIp; }
    public Instant revokedAt() { return revokedAt; }
    public RevokeReason revokedReason() { return revokedReason; }
}

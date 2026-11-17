package lms.identity.auth.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
class RefreshTokenJpaEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Tenant của phiên; null với phiên nền tảng hoặc token phát hành trước V12. */
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "platform_session", nullable = false)
    private boolean platformSession;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean revoked;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** STAFF hoặc STOREFRONT (V18); refresh giữ kênh của token gốc. */
    @Column(name = "channel", nullable = false, length = 20)
    private String channel = "STAFF";

    /** Họ phiên (V24, ADR-0017): giữ nguyên khi xoay vòng. */
    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "session_started_at", nullable = false)
    private Instant sessionStartedAt;

    @Column(name = "device_summary", length = 100)
    private String deviceSummary;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 20)
    private String revokedReason;

    protected RefreshTokenJpaEntity() {
    }

    RefreshTokenJpaEntity(UUID id, UUID userId, UUID tenantId, boolean platformSession, String tokenHash,
                          Instant expiresAt, boolean revoked, Instant createdAt, String channel, UUID sessionId,
                          Instant sessionStartedAt, String deviceSummary, String clientIp, Instant revokedAt,
                          String revokedReason) {
        this(id, userId, tenantId, platformSession, tokenHash, expiresAt, revoked, createdAt, channel);
        this.sessionId = sessionId;
        this.sessionStartedAt = sessionStartedAt;
        this.deviceSummary = deviceSummary;
        this.clientIp = clientIp;
        this.revokedAt = revokedAt;
        this.revokedReason = revokedReason;
    }

    RefreshTokenJpaEntity(UUID id, UUID userId, UUID tenantId, boolean platformSession, String tokenHash,
                          Instant expiresAt, boolean revoked, Instant createdAt, String channel) {
        this.id = id;
        this.userId = userId;
        this.tenantId = tenantId;
        this.platformSession = platformSession;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = revoked;
        this.createdAt = createdAt;
        this.channel = channel;
        this.sessionId = id;
        this.sessionStartedAt = createdAt;
    }

    UUID getId() { return id; }
    UUID getUserId() { return userId; }
    UUID getTenantId() { return tenantId; }
    boolean isPlatformSession() { return platformSession; }
    String getTokenHash() { return tokenHash; }
    Instant getExpiresAt() { return expiresAt; }
    boolean isRevoked() { return revoked; }
    Instant getCreatedAt() { return createdAt; }
    String getChannel() { return channel; }
    UUID getSessionId() { return sessionId; }
    Instant getSessionStartedAt() { return sessionStartedAt; }
    String getDeviceSummary() { return deviceSummary; }
    String getClientIp() { return clientIp; }
    Instant getRevokedAt() { return revokedAt; }
    String getRevokedReason() { return revokedReason; }
}

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

    protected RefreshTokenJpaEntity() {
    }

    RefreshTokenJpaEntity(UUID id, UUID userId, UUID tenantId, boolean platformSession, String tokenHash,
                          Instant expiresAt, boolean revoked, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.tenantId = tenantId;
        this.platformSession = platformSession;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = revoked;
        this.createdAt = createdAt;
    }

    UUID getId() { return id; }
    UUID getUserId() { return userId; }
    UUID getTenantId() { return tenantId; }
    boolean isPlatformSession() { return platformSession; }
    String getTokenHash() { return tokenHash; }
    Instant getExpiresAt() { return expiresAt; }
    boolean isRevoked() { return revoked; }
    Instant getCreatedAt() { return createdAt; }
}

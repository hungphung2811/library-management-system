package lms.identity.apiclient.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Bảng toàn cục application_credentials (không @TenantId, không RLS). Chỉ có hash của secret. */
@Entity
@Table(name = "application_credentials")
class ApplicationCredentialJpaEntity {

    @Id
    private UUID id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "secret_hash", nullable = false, updatable = false, length = 64)
    private String secretHash;

    @Column(name = "secret_hint", nullable = false, updatable = false)
    private String secretHint;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Column(name = "revoke_reason")
    private String revokeReason;

    protected ApplicationCredentialJpaEntity() {
    }

    ApplicationCredentialJpaEntity(UUID id, UUID applicationId, UUID tenantId, String secretHash,
                                   String secretHint, String status, Instant createdAt, UUID createdBy,
                                   Instant expiresAt, Instant lastUsedAt, Instant revokedAt, UUID revokedBy,
                                   String revokeReason) {
        this.id = id;
        this.applicationId = applicationId;
        this.tenantId = tenantId;
        this.secretHash = secretHash;
        this.secretHint = secretHint;
        this.status = status;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.expiresAt = expiresAt;
        this.lastUsedAt = lastUsedAt;
        this.revokedAt = revokedAt;
        this.revokedBy = revokedBy;
        this.revokeReason = revokeReason;
    }

    UUID getId() { return id; }
    UUID getApplicationId() { return applicationId; }
    UUID getTenantId() { return tenantId; }
    String getSecretHash() { return secretHash; }
    String getSecretHint() { return secretHint; }
    String getStatus() { return status; }
    Instant getCreatedAt() { return createdAt; }
    UUID getCreatedBy() { return createdBy; }
    Instant getExpiresAt() { return expiresAt; }
    Instant getLastUsedAt() { return lastUsedAt; }
    Instant getRevokedAt() { return revokedAt; }
    UUID getRevokedBy() { return revokedBy; }
    String getRevokeReason() { return revokeReason; }

    @Override
    public String toString() {
        return "ApplicationCredentialJpaEntity[id=" + id + ", applicationId=" + applicationId + "]";
    }
}

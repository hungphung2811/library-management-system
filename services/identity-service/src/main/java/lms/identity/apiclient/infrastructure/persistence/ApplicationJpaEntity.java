package lms.identity.apiclient.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Bảng toàn cục applications (KHÔNG @TenantId, KHÔNG RLS; nằm trong TENANT_EXEMPT_TABLES, SaaS API Authentication D5):
 * token endpoint (I2) tra client_id khi chưa biết tenant. Mọi truy vấn của adapter lọc tenant_id tường minh.
 */
@Entity
@Table(name = "applications")
class ApplicationJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private String kind;

    @Column(nullable = false)
    private String status;

    @Column(name = "site_key")
    private String siteKey;

    @Column(name = "rate_limit_per_minute")
    private Integer rateLimitPerMinute;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(nullable = false)
    private long version;

    protected ApplicationJpaEntity() {
    }

    ApplicationJpaEntity(UUID id, UUID tenantId, String name, String kind, String status, String siteKey,
                         Integer rateLimitPerMinute, Instant createdAt, UUID createdBy, Instant updatedAt,
                         UUID updatedBy, Instant deletedAt, long version) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.kind = kind;
        this.status = status;
        this.siteKey = siteKey;
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
        this.deletedAt = deletedAt;
        this.version = version;
    }

    UUID getId() { return id; }
    UUID getTenantId() { return tenantId; }
    String getName() { return name; }
    String getKind() { return kind; }
    String getStatus() { return status; }
    String getSiteKey() { return siteKey; }
    Integer getRateLimitPerMinute() { return rateLimitPerMinute; }
    Instant getCreatedAt() { return createdAt; }
    UUID getCreatedBy() { return createdBy; }
    Instant getUpdatedAt() { return updatedAt; }
    UUID getUpdatedBy() { return updatedBy; }
    Instant getDeletedAt() { return deletedAt; }
    long getVersion() { return version; }
}

package lms.identity.tenant.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Bảng toàn cục (không @TenantId, không RLS; nằm trong TENANT_EXEMPT_TABLES, ADR-0013 2.5): platform admin ghi, cấp token
 * storefront đọc khi chưa có ngữ cảnh tenant. Truy vấn luôn lọc tenant_id tường minh.
 */
@Entity
@Table(name = "tenant_storefront_origins")
class TenantStorefrontOriginJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String origin;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    /** Null = áp cho mọi Application kiểu website của tenant (V32). */
    @Column(name = "application_id")
    private UUID applicationId;

    protected TenantStorefrontOriginJpaEntity() {
    }

    TenantStorefrontOriginJpaEntity(UUID id, UUID tenantId, String origin, boolean enabled, Instant createdAt,
                                    UUID createdBy, Instant updatedAt, UUID updatedBy, UUID applicationId) {
        this.id = id;
        this.tenantId = tenantId;
        this.origin = origin;
        this.enabled = enabled;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
        this.applicationId = applicationId;
    }

    UUID getId() { return id; }
    UUID getTenantId() { return tenantId; }
    String getOrigin() { return origin; }
    boolean isEnabled() { return enabled; }
    Instant getCreatedAt() { return createdAt; }
    UUID getCreatedBy() { return createdBy; }
    Instant getUpdatedAt() { return updatedAt; }
    UUID getUpdatedBy() { return updatedBy; }
    UUID getApplicationId() { return applicationId; }
}

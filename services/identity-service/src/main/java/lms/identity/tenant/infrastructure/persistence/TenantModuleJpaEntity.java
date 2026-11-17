package lms.identity.tenant.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Bảng toàn cục (không @TenantId, không RLS): TokenService đọc trước khi vào ngữ cảnh tenant (ADR-0009). */
@Entity
@Table(name = "tenant_modules")
@IdClass(TenantModuleId.class)
class TenantModuleJpaEntity {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Id
    @Column(name = "module")
    private String module;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected TenantModuleJpaEntity() {
    }

    TenantModuleJpaEntity(UUID tenantId, String module, boolean enabled, Instant updatedAt, UUID updatedBy) {
        this.tenantId = tenantId;
        this.module = module;
        this.enabled = enabled;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    UUID getTenantId() { return tenantId; }
    String getModule() { return module; }
    boolean isEnabled() { return enabled; }
    Instant getUpdatedAt() { return updatedAt; }
    UUID getUpdatedBy() { return updatedBy; }
}

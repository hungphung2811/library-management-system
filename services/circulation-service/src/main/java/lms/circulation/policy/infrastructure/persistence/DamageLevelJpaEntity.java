package lms.circulation.policy.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/**
 * Bậc hỏng do tenant định nghĩa: tenant_id do Hibernate điền/lọc (@TenantId), RLS lọc thêm ở DB (migration circulation
 * V12). Khóa lạc quan bằng cột version. Mọi truy vấn là JPQL/derived query, không có SQL native.
 */
@Entity
@Table(name = "damage_levels")
class DamageLevelJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 100)
    private String label;

    @Column(nullable = false, precision = 6, scale = 4)
    private BigDecimal rate;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected DamageLevelJpaEntity() {
    }

    /** Dòng mới: tenant lấy từ TenantContext (không có thì TenantRequiredException, fail-closed). */
    DamageLevelJpaEntity(UUID id, String code, Instant createdAt) {
        this.tenantId = TenantContext.require();
        this.id = id;
        this.code = code;
        this.createdAt = createdAt;
    }

    void apply(String label, BigDecimal rate, int sortOrder, boolean active, Instant updatedAt, UUID updatedBy) {
        this.label = label;
        this.rate = rate;
        this.sortOrder = sortOrder;
        this.active = active;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    String getCode() { return code; }
    String getLabel() { return label; }
    BigDecimal getRate() { return rate; }
    int getSortOrder() { return sortOrder; }
    boolean isActive() { return active; }
    long getVersion() { return version == null ? 0 : version; }
    Instant getCreatedAt() { return createdAt; }
    Instant getUpdatedAt() { return updatedAt; }
    UUID getUpdatedBy() { return updatedBy; }
}

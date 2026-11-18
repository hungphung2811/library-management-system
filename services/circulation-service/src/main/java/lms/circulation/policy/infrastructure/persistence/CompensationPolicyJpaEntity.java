package lms.circulation.policy.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lms.circulation.policy.domain.PriceSourceKind;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/**
 * Chính sách đền bù, một dòng mỗi tenant: tenant_id do Hibernate điền/lọc (@TenantId), RLS lọc thêm ở DB (migration
 * circulation V12). Khóa lạc quan bằng cột version.
 */
@Entity
@Table(name = "compensation_policies")
class CompensationPolicyJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "lost_rate", nullable = false, precision = 6, scale = 4)
    private BigDecimal lostRate;
    @Enumerated(EnumType.STRING)
    @Column(name = "price_source", nullable = false, length = 20)
    private PriceSourceKind priceSource;
    @Column(name = "min_amount", precision = 14, scale = 0)
    private BigDecimal minAmount;
    @Column(name = "max_amount", precision = 14, scale = 0)
    private BigDecimal maxAmount;
    @Column(name = "found_window_days", nullable = false)
    private int foundWindowDays;
    @Column(name = "refund_processing_fee", nullable = false, precision = 14, scale = 0)
    private BigDecimal refundProcessingFee;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "updated_by")
    private UUID updatedBy;

    protected CompensationPolicyJpaEntity() {
    }

    /** Dòng mới: tenant lấy từ TenantContext (không có thì TenantRequiredException, fail-closed). */
    CompensationPolicyJpaEntity(UUID id) {
        this.tenantId = TenantContext.require();
        this.id = id;
    }

    void apply(BigDecimal lostRate, PriceSourceKind priceSource, BigDecimal minAmount, BigDecimal maxAmount,
               int foundWindowDays, BigDecimal refundProcessingFee, Instant updatedAt, UUID updatedBy) {
        this.lostRate = lostRate;
        this.priceSource = priceSource;
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.foundWindowDays = foundWindowDays;
        this.refundProcessingFee = refundProcessingFee;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    long getVersion() { return version == null ? 0 : version; }
    BigDecimal getLostRate() { return lostRate; }
    PriceSourceKind getPriceSource() { return priceSource; }
    BigDecimal getMinAmount() { return minAmount; }
    BigDecimal getMaxAmount() { return maxAmount; }
    int getFoundWindowDays() { return foundWindowDays; }
    BigDecimal getRefundProcessingFee() { return refundProcessingFee; }
    Instant getUpdatedAt() { return updatedAt; }
    UUID getUpdatedBy() { return updatedBy; }
}

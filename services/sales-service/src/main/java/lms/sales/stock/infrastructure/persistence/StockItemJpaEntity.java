package lms.sales.stock.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.TenantId;

/**
 * Mặt hàng bán. Bộ đếm on_hand/reserved chỉ đổi bằng JPQL bulk update có điều kiện (StockPersistenceAdapter.tryApply);
 * cột bộ đếm {@code updatable = false} và entity {@code @DynamicUpdate} để một lần flush thuộc tính khác
 * (giá, ngưỡng, active) không bao giờ ghi đè bộ đếm bằng giá trị cũ.
 */
@Entity
@Table(name = "stock_items")
@DynamicUpdate
class StockItemJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "title_id", nullable = false, updatable = false)
    private UUID titleId;

    @Column(name = "on_hand", nullable = false, updatable = false)
    private int onHand;

    @Column(name = "reserved", nullable = false, updatable = false)
    private int reserved;

    @Column(name = "selling_price")
    private Long sellingPrice;

    @Column(name = "low_stock_threshold")
    private Integer lowStockThreshold;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StockItemJpaEntity() {
    }

    StockItemJpaEntity(UUID id, UUID titleId, int onHand, int reserved, Long sellingPrice, Integer lowStockThreshold,
                       boolean active) {
        this.tenantId = TenantContext.require();
        this.id = id;
        this.titleId = titleId;
        this.onHand = onHand;
        this.reserved = reserved;
        this.sellingPrice = sellingPrice;
        this.lowStockThreshold = lowStockThreshold;
        this.active = active;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    void updateAttributes(Long sellingPrice, Integer lowStockThreshold, boolean active) {
        this.sellingPrice = sellingPrice;
        this.lowStockThreshold = lowStockThreshold;
        this.active = active;
        this.updatedAt = Instant.now();
    }

    UUID getId() { return id; }
    UUID getTenantId() { return tenantId; }
    UUID getTitleId() { return titleId; }
    int getOnHand() { return onHand; }
    int getReserved() { return reserved; }
    Long getSellingPrice() { return sellingPrice; }
    Integer getLowStockThreshold() { return lowStockThreshold; }
    boolean isActive() { return active; }
}

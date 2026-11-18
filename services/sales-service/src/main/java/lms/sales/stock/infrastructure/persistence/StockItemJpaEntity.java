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

    /** Bản sao giá bìa chỉ để sắp (ADR-0019 2.1); ghi bằng JPQL bulk ở adapter. */
    @Column(name = "cover_price_snapshot")
    private Long coverPriceSnapshot;

    /** Snapshot id tác phẩm của catalog để gộp biến thể (ADR-0026, V14); ghi bằng JPQL bulk ở adapter. NULL = chưa biết. */
    @Column(name = "work_id")
    private UUID workId;

    /** Cột sinh COALESCE(selling_price, cover_price_snapshot): chỉ đọc, DB tự tính. */
    @Column(name = "effective_price", insertable = false, updatable = false)
    private Long effectivePrice;

    /** Thông tin quản lý tùy chọn (V8). cost_price và note là dữ liệu nội bộ. */
    @Column(name = "sku", length = 40)
    private String sku;

    @Column(name = "supplier")
    private String supplier;

    @Column(name = "cost_price")
    private Long costPrice;

    @Column(name = "bin_location", length = 64)
    private String binLocation;

    @Column(name = "reorder_quantity")
    private Integer reorderQuantity;

    /** Cập nhật bằng JPQL bulk khi có phiếu nhập (touchLastRestocked) hoặc qua API chi tiết. */
    @Column(name = "last_restocked_at")
    private Instant lastRestockedAt;

    @Column(name = "note", length = 500)
    private String note;

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

    void updateDetails(String sku, String supplier, Long costPrice, String binLocation, Integer reorderQuantity,
                       Instant lastRestockedAt, String note) {
        this.sku = sku;
        this.supplier = supplier;
        this.costPrice = costPrice;
        this.binLocation = binLocation;
        this.reorderQuantity = reorderQuantity;
        this.lastRestockedAt = lastRestockedAt;
        this.note = note;
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
    Instant getCreatedAt() { return createdAt; }
    Long getCoverPriceSnapshot() { return coverPriceSnapshot; }
    Long getEffectivePrice() { return effectivePrice; }
    UUID getWorkId() { return workId; }
    String getSku() { return sku; }
    String getSupplier() { return supplier; }
    Long getCostPrice() { return costPrice; }
    String getBinLocation() { return binLocation; }
    Integer getReorderQuantity() { return reorderQuantity; }
    Instant getLastRestockedAt() { return lastRestockedAt; }
    String getNote() { return note; }
}

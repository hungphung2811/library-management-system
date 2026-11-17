package lms.sales.stock.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/** Dòng phiếu nhập: bất biến sau khi tạo (phiếu DRAFT không sửa dòng ở pha này). */
@Entity
@Table(name = "goods_receipt_lines")
class GoodsReceiptLineJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "receipt_id", nullable = false, updatable = false)
    private UUID receiptId;

    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    @Column(name = "title_id", nullable = false, updatable = false)
    private UUID titleId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "unit_cost", nullable = false, updatable = false)
    private long unitCost;

    protected GoodsReceiptLineJpaEntity() {
    }

    GoodsReceiptLineJpaEntity(UUID receiptId, int lineNo, UUID titleId, int quantity, long unitCost) {
        this.tenantId = TenantContext.require();
        this.id = UUID.randomUUID();
        this.receiptId = receiptId;
        this.lineNo = lineNo;
        this.titleId = titleId;
        this.quantity = quantity;
        this.unitCost = unitCost;
    }

    UUID getReceiptId() { return receiptId; }
    int getLineNo() { return lineNo; }
    UUID getTitleId() { return titleId; }
    int getQuantity() { return quantity; }
    long getUnitCost() { return unitCost; }
}

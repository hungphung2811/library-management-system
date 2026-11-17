package lms.sales.ordering.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/** Dòng đơn kèm snapshot tên/ISBN/đơn giá lúc bán. Chỉ các cột tiền đổi được (giảm giá đơn online tính lại). */
@Entity
@Table(name = "sales_order_lines")
class SalesOrderLineJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    @Column(name = "title_id", nullable = false, updatable = false)
    private UUID titleId;

    @Column(name = "title_name", nullable = false, updatable = false)
    private String titleName;

    @Column(name = "isbn", updatable = false)
    private String isbn;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, updatable = false)
    private long unitPrice;

    @Column(name = "line_discount", nullable = false)
    private long lineDiscount;

    @Column(name = "allocated_order_discount", nullable = false)
    private long allocatedOrderDiscount;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "vat_amount", nullable = false)
    private long vatAmount;

    @Column(name = "line_total", nullable = false)
    private long lineTotal;

    protected SalesOrderLineJpaEntity() {
    }

    @SuppressWarnings("java:S107")
    SalesOrderLineJpaEntity(UUID id, UUID orderId, int lineNo, UUID titleId, String titleName, String isbn,
                            int quantity, long unitPrice, long lineDiscount, long allocatedOrderDiscount,
                            long amount, long vatAmount, long lineTotal) {
        this.tenantId = TenantContext.require();
        this.id = id;
        this.orderId = orderId;
        this.lineNo = lineNo;
        this.titleId = titleId;
        this.titleName = titleName;
        this.isbn = isbn;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.lineDiscount = lineDiscount;
        this.allocatedOrderDiscount = allocatedOrderDiscount;
        this.amount = amount;
        this.vatAmount = vatAmount;
        this.lineTotal = lineTotal;
    }

    void applyAmounts(long lineDiscount, long allocatedOrderDiscount, long amount, long vatAmount, long lineTotal) {
        this.lineDiscount = lineDiscount;
        this.allocatedOrderDiscount = allocatedOrderDiscount;
        this.amount = amount;
        this.vatAmount = vatAmount;
        this.lineTotal = lineTotal;
    }

    UUID getId() { return id; }
    UUID getOrderId() { return orderId; }
    int getLineNo() { return lineNo; }
    UUID getTitleId() { return titleId; }
    String getTitleName() { return titleName; }
    String getIsbn() { return isbn; }
    int getQuantity() { return quantity; }
    long getUnitPrice() { return unitPrice; }
    long getLineDiscount() { return lineDiscount; }
    long getAllocatedOrderDiscount() { return allocatedOrderDiscount; }
    long getAmount() { return amount; }
    long getVatAmount() { return vatAmount; }
    long getLineTotal() { return lineTotal; }
}

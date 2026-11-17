package lms.sales.ordering.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lms.sales.ordering.domain.ReturnLine;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/** Dòng phiếu trả (bất biến). {@code titleId} không lưu: lấy từ dòng đơn khi dựng lại phiếu. */
@Entity
@Table(name = "sales_return_lines")
class SalesReturnLineJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "return_id", nullable = false, updatable = false)
    private UUID returnId;

    @Column(name = "order_line_id", nullable = false, updatable = false)
    private UUID orderLineId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "disposition", nullable = false, updatable = false)
    private String disposition;

    @Column(name = "refund_amount", nullable = false, updatable = false)
    private long refundAmount;

    protected SalesReturnLineJpaEntity() {
    }

    SalesReturnLineJpaEntity(UUID returnId, ReturnLine l) {
        this.tenantId = TenantContext.require();
        this.id = l.id();
        this.returnId = returnId;
        this.orderLineId = l.orderLineId();
        this.quantity = l.quantity();
        this.disposition = l.disposition().name();
        this.refundAmount = l.refundAmount().amount();
    }

    UUID getId() { return id; }
    UUID getReturnId() { return returnId; }
    UUID getOrderLineId() { return orderLineId; }
    int getQuantity() { return quantity; }
    String getDisposition() { return disposition; }
    long getRefundAmount() { return refundAmount; }
}

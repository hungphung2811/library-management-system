package lms.sales.ordering.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lms.sales.ordering.domain.SalesReturn;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/** Phiếu trả hàng (bất biến sau khi tạo). */
@Entity
@Table(name = "sales_returns")
class SalesReturnJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "return_no", nullable = false, updatable = false)
    private String returnNo;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "reason", nullable = false, updatable = false)
    private String reason;

    @Column(name = "late", nullable = false, updatable = false)
    private boolean late;

    @Column(name = "late_reason", updatable = false)
    private String lateReason;

    @Column(name = "days_overdue", nullable = false, updatable = false)
    private int daysOverdue;

    @Column(name = "refund_total", nullable = false, updatable = false)
    private long refundTotal;

    @Column(name = "client_request_id", nullable = false, updatable = false)
    private UUID clientRequestId;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SalesReturnJpaEntity() {
    }

    SalesReturnJpaEntity(SalesReturn r) {
        this.tenantId = TenantContext.require();
        this.id = r.id();
        this.returnNo = r.returnNo();
        this.orderId = r.orderId();
        this.reason = r.reason();
        this.late = r.late();
        this.lateReason = r.lateReason();
        this.daysOverdue = r.daysOverdue();
        this.refundTotal = r.refundTotal().amount();
        this.clientRequestId = r.clientRequestId();
        this.createdBy = r.createdBy();
        this.createdAt = r.createdAt();
    }

    UUID getId() { return id; }
    UUID getTenantId() { return tenantId; }
    String getReturnNo() { return returnNo; }
    UUID getOrderId() { return orderId; }
    String getReason() { return reason; }
    boolean isLate() { return late; }
    String getLateReason() { return lateReason; }
    int getDaysOverdue() { return daysOverdue; }
    UUID getClientRequestId() { return clientRequestId; }
    UUID getCreatedBy() { return createdBy; }
    Instant getCreatedAt() { return createdAt; }
}

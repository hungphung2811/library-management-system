package lms.sales.ordering.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lms.sales.ordering.domain.Refund;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/** Khoản hoàn tiền; cập nhật khi đánh dấu đã hoàn (khóa lạc quan @Version). */
@Entity
@Table(name = "refunds")
class RefundJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "source", nullable = false, updatable = false)
    private String source;

    @Column(name = "return_id", updatable = false)
    private UUID returnId;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "method")
    private String method;

    @Column(name = "reference")
    private String reference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "refunded_by")
    private UUID refundedBy;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    /** Ca thu ngân của người đánh dấu đã hoàn (ADR-0030). */
    @Column(name = "shift_id")
    private UUID shiftId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected RefundJpaEntity() {
    }

    RefundJpaEntity(Refund r) {
        this.tenantId = TenantContext.require();
        this.id = r.id();
        this.orderId = r.orderId();
        this.source = r.source().name();
        this.returnId = r.returnId();
        this.amount = r.amount().amount();
        this.createdAt = r.createdAt();
        apply(r);
    }

    void apply(Refund r) {
        this.status = r.status().name();
        this.method = r.method() == null ? null : r.method().name();
        this.reference = r.reference();
        this.refundedBy = r.refundedBy();
        this.refundedAt = r.refundedAt();
        this.shiftId = r.shiftId();
    }

    UUID getId() { return id; }
    UUID getTenantId() { return tenantId; }
    UUID getOrderId() { return orderId; }
    String getSource() { return source; }
    UUID getReturnId() { return returnId; }
    long getAmount() { return amount; }
    String getStatus() { return status; }
    String getMethod() { return method; }
    String getReference() { return reference; }
    Instant getCreatedAt() { return createdAt; }
    UUID getRefundedBy() { return refundedBy; }
    Instant getRefundedAt() { return refundedAt; }
    UUID getShiftId() { return shiftId; }
    long getVersion() { return version; }
}

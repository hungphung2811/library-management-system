package lms.sales.ordering.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/** Bản ghi thu tiền của đơn (chỉ chèn thêm ở pha 1). */
@Entity
@Table(name = "payment_records")
class PaymentRecordJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "method", nullable = false, updatable = false)
    private String method;

    @Column(name = "reference", updatable = false)
    private String reference;

    @Column(name = "recorded_by", updatable = false)
    private UUID recordedBy;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    /** Ca thu ngân nhận tiền (ADR-0030), null khi không gắn ca. */
    @Column(name = "shift_id", updatable = false)
    private UUID shiftId;

    protected PaymentRecordJpaEntity() {
    }

    PaymentRecordJpaEntity(UUID id, UUID orderId, long amount, String method, String reference, UUID recordedBy,
                           Instant recordedAt, UUID shiftId) {
        this.tenantId = TenantContext.require();
        this.id = id;
        this.orderId = orderId;
        this.amount = amount;
        this.method = method;
        this.reference = reference;
        this.recordedBy = recordedBy;
        this.recordedAt = recordedAt;
        this.shiftId = shiftId;
    }

    UUID getId() { return id; }
    UUID getOrderId() { return orderId; }
    long getAmount() { return amount; }
    String getMethod() { return method; }
    String getReference() { return reference; }
    UUID getRecordedBy() { return recordedBy; }
    Instant getRecordedAt() { return recordedAt; }
    UUID getShiftId() { return shiftId; }
}

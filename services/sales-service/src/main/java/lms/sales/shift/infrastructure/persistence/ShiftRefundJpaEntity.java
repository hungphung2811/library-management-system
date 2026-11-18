package lms.sales.shift.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

/** Góc nhìn CHỈ ĐỌC của bảng refunds cho báo cáo ca (xem {@link ShiftPaymentJpaEntity}). */
@Entity
@Immutable
@Table(name = "refunds")
class ShiftRefundJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "shift_id", updatable = false)
    private UUID shiftId;

    @Column(name = "status", nullable = false, updatable = false)
    private String status;

    @Column(name = "method", updatable = false)
    private String method;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "refunded_at", updatable = false)
    private Instant refundedAt;

    protected ShiftRefundJpaEntity() {
    }
}

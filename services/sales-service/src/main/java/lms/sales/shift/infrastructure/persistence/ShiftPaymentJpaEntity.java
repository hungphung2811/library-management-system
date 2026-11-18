package lms.sales.shift.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

/**
 * Góc nhìn CHỈ ĐỌC của bảng payment_records cho báo cáo ca (ADR-0030): context shift không phụ thuộc package ordering nên
 * tự ánh xạ các cột cần để gom nhóm theo (tenant_id, shift_id). Ghi vào bảng này chỉ do ordering làm.
 */
@Entity
@Immutable
@Table(name = "payment_records")
class ShiftPaymentJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "shift_id", updatable = false)
    private UUID shiftId;

    @Column(name = "method", nullable = false, updatable = false)
    private String method;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected ShiftPaymentJpaEntity() {
    }
}

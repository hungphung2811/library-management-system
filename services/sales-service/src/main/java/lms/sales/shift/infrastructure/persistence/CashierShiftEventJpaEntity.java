package lms.sales.shift.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lms.sales.shift.domain.ShiftEvent;
import lms.sales.shift.domain.ShiftEventType;
import lms.tenant.TenantContext;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/** Lịch sử ca: chỉ chèn thêm. */
@Entity
@Table(name = "cashier_shift_events")
class CashierShiftEventJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "shift_id", nullable = false, updatable = false)
    private UUID shiftId;

    @Column(name = "type", nullable = false, updatable = false)
    private String type;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> snapshot;

    protected CashierShiftEventJpaEntity() {
    }

    CashierShiftEventJpaEntity(ShiftEvent e) {
        this.tenantId = TenantContext.require();
        this.id = e.id();
        this.shiftId = e.shiftId();
        this.type = e.type().name();
        this.actorId = e.actorId();
        this.occurredAt = e.occurredAt();
        this.snapshot = e.snapshot();
    }

    ShiftEvent toDomain() {
        return new ShiftEvent(id, shiftId, ShiftEventType.valueOf(type), actorId, occurredAt, snapshot);
    }
}

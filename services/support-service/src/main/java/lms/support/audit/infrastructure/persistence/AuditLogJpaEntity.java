package lms.support.audit.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** Audit log thuộc một tenant: tenant_id do Hibernate lọc theo TenantContext (@TenantId), RLS lọc thêm ở DB. */
@Entity
@Table(name = "audit_logs")
class AuditLogJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    protected AuditLogJpaEntity() {
    }

    UUID getId() { return id; }
    UUID getEventId() { return eventId; }
    String getEventType() { return eventType; }
    String getAggregateId() { return aggregateId; }
    Instant getOccurredAt() { return occurredAt; }
    Instant getRecordedAt() { return recordedAt; }
    String getPayload() { return payload; }
}

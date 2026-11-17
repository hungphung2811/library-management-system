package lms.sales.shared.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/** Dòng nhật ký audit (chỉ chèn thêm). {@code data} không chứa dữ liệu cá nhân. */
@Entity
@Table(name = "sales_audit_log")
class SalesAuditJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "action", nullable = false, updatable = false)
    private String action;

    @Column(name = "aggregate_type", nullable = false, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", updatable = false)
    private UUID aggregateId;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data", columnDefinition = "jsonb", updatable = false)
    private Map<String, Object> data;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected SalesAuditJpaEntity() {
    }

    SalesAuditJpaEntity(UUID id, String action, String aggregateType, UUID aggregateId, UUID actorId,
                        Instant occurredAt, Map<String, Object> data) {
        this.tenantId = TenantContext.require();
        this.id = id;
        this.action = action;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.actorId = actorId;
        this.occurredAt = occurredAt;
        this.data = data;
    }

    UUID getId() { return id; }
    String getAction() { return action; }
    String getAggregateType() { return aggregateType; }
    UUID getAggregateId() { return aggregateId; }
    UUID getActorId() { return actorId; }
    Instant getOccurredAt() { return occurredAt; }
    Map<String, Object> getData() { return data; }
}

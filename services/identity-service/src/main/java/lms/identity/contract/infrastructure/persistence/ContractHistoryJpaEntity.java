package lms.identity.contract.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Bảng chỉ thêm tenant_contract_history (bất biến). */
@Entity
@Immutable
@Table(name = "tenant_contract_history")
class ContractHistoryJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "contract_id")
    private UUID contractId;

    @Column(nullable = false)
    private String type;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_type", nullable = false)
    private String actorType;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> data;

    protected ContractHistoryJpaEntity() {
    }

    ContractHistoryJpaEntity(UUID id, UUID tenantId, UUID contractId, String type, UUID actorId, String actorType,
                             Instant occurredAt, Map<String, Object> data) {
        this.id = id;
        this.tenantId = tenantId;
        this.contractId = contractId;
        this.type = type;
        this.actorId = actorId;
        this.actorType = actorType;
        this.occurredAt = occurredAt;
        this.data = data;
    }

    UUID getId() { return id; }
    UUID getTenantId() { return tenantId; }
    UUID getContractId() { return contractId; }
    String getType() { return type; }
    UUID getActorId() { return actorId; }
    String getActorType() { return actorType; }
    Instant getOccurredAt() { return occurredAt; }
    Map<String, Object> getData() { return data; }
}

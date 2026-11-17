package lms.sales.stock.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/** Dòng sổ cái tồn kho: chỉ chèn thêm, không có đường cập nhật. */
@Entity
@Table(name = "stock_ledger")
class StockLedgerJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "stock_item_id", nullable = false, updatable = false)
    private UUID stockItemId;

    @Column(name = "type", nullable = false, updatable = false)
    private String movementType;

    @Column(name = "delta_on_hand", nullable = false, updatable = false)
    private int deltaOnHand;

    @Column(name = "delta_reserved", nullable = false, updatable = false)
    private int deltaReserved;

    @Column(name = "ref_type", updatable = false)
    private String refType;

    @Column(name = "ref_id", updatable = false)
    private UUID refId;

    @Column(name = "reason", updatable = false)
    private String reason;

    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected StockLedgerJpaEntity() {
    }

    StockLedgerJpaEntity(UUID id, UUID stockItemId, String movementType, int deltaOnHand, int deltaReserved,
                         String refType, UUID refId, String reason, UUID actorId, Instant occurredAt) {
        this.tenantId = TenantContext.require();
        this.id = id;
        this.stockItemId = stockItemId;
        this.movementType = movementType;
        this.deltaOnHand = deltaOnHand;
        this.deltaReserved = deltaReserved;
        this.refType = refType;
        this.refId = refId;
        this.reason = reason;
        this.actorId = actorId;
        this.occurredAt = occurredAt;
    }

    UUID getId() { return id; }
    UUID getTenantId() { return tenantId; }
    UUID getStockItemId() { return stockItemId; }
    String getMovementType() { return movementType; }
    int getDeltaOnHand() { return deltaOnHand; }
    int getDeltaReserved() { return deltaReserved; }
    String getRefType() { return refType; }
    UUID getRefId() { return refId; }
    String getReason() { return reason; }
    UUID getActorId() { return actorId; }
    Instant getOccurredAt() { return occurredAt; }
}

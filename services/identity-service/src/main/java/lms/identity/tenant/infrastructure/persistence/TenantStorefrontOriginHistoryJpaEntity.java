package lms.identity.tenant.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Bảng chỉ thêm tenant_storefront_origin_history (trigger chặn UPDATE/DELETE; identity_app bị REVOKE). Toàn cục. */
@Entity
@Immutable
@Table(name = "tenant_storefront_origin_history")
class TenantStorefrontOriginHistoryJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "origin_id", nullable = false)
    private UUID originId;

    @Column(nullable = false)
    private String origin;

    @Column(nullable = false)
    private String action;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected TenantStorefrontOriginHistoryJpaEntity() {
    }

    TenantStorefrontOriginHistoryJpaEntity(UUID id, UUID tenantId, UUID originId, String origin, String action,
                                           UUID actorId, Instant occurredAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.originId = originId;
        this.origin = origin;
        this.action = action;
        this.actorId = actorId;
        this.occurredAt = occurredAt;
    }

    UUID getId() { return id; }
    UUID getTenantId() { return tenantId; }
    UUID getOriginId() { return originId; }
    String getOrigin() { return origin; }
    String getAction() { return action; }
    UUID getActorId() { return actorId; }
    Instant getOccurredAt() { return occurredAt; }
}

package lms.identity.apiclient.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Bảng chỉ thêm application_history (trigger chặn UPDATE/DELETE; identity_app bị REVOKE). Toàn cục. */
@Entity
@Immutable
@Table(name = "application_history")
class ApplicationHistoryJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "credential_id")
    private UUID credentialId;

    @Column(nullable = false)
    private String action;

    private String detail;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected ApplicationHistoryJpaEntity() {
    }

    ApplicationHistoryJpaEntity(UUID id, UUID tenantId, UUID applicationId, UUID credentialId, String action,
                                String detail, UUID actorId, Instant occurredAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.applicationId = applicationId;
        this.credentialId = credentialId;
        this.action = action;
        this.detail = detail;
        this.actorId = actorId;
        this.occurredAt = occurredAt;
    }

    UUID getId() { return id; }
    UUID getTenantId() { return tenantId; }
    UUID getApplicationId() { return applicationId; }
    UUID getCredentialId() { return credentialId; }
    String getAction() { return action; }
    String getDetail() { return detail; }
    UUID getActorId() { return actorId; }
    Instant getOccurredAt() { return occurredAt; }
}

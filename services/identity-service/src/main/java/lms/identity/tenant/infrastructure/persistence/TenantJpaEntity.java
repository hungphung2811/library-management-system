package lms.identity.tenant.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lms.identity.tenant.domain.TenantStatus;

@Entity
@Table(name = "tenants")
class TenantJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TenantJpaEntity() {
    }

    TenantJpaEntity(UUID id, String code, String name, UUID organizationId, TenantStatus status, Instant createdAt) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.organizationId = organizationId;
        this.status = status;
        this.createdAt = createdAt;
    }

    UUID getId() { return id; }
    String getCode() { return code; }
    String getName() { return name; }
    UUID getOrganizationId() { return organizationId; }
    TenantStatus getStatus() { return status; }
    Instant getCreatedAt() { return createdAt; }
}

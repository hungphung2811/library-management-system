package lms.identity.apiclient.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Bảng nối application_scopes (toàn cục, không có cột tenant_id: tenant suy ra từ applications). */
@Entity
@Table(name = "application_scopes")
@IdClass(ApplicationScopeId.class)
class ApplicationScopeJpaEntity {

    @Id
    @Column(name = "application_id")
    private UUID applicationId;

    @Id
    @Column(name = "scope")
    private String scope;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "granted_by")
    private UUID grantedBy;

    protected ApplicationScopeJpaEntity() {
    }

    ApplicationScopeJpaEntity(UUID applicationId, String scope, Instant grantedAt, UUID grantedBy) {
        this.applicationId = applicationId;
        this.scope = scope;
        this.grantedAt = grantedAt;
        this.grantedBy = grantedBy;
    }

    UUID getApplicationId() { return applicationId; }
    String getScope() { return scope; }
}

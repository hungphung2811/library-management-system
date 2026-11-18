package lms.identity.apiclient.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.identity.apiclient.domain.ApplicationCredential;
import lms.identity.apiclient.domain.ApplicationCredentialRepository;
import lms.identity.apiclient.domain.CredentialStatus;
import lms.identity.apiclient.domain.RevokeReason;
import org.springframework.stereotype.Repository;

/** Bảng toàn cục application_credentials. Người gọi đã xác nhận Application thuộc tenant. */
@Repository
class ApplicationCredentialPersistenceAdapter implements ApplicationCredentialRepository {

    private final SpringDataApplicationCredentialRepository jpa;

    ApplicationCredentialPersistenceAdapter(SpringDataApplicationCredentialRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ApplicationCredential save(ApplicationCredential c) {
        jpa.saveAndFlush(new ApplicationCredentialJpaEntity(c.id(), c.applicationId(), c.tenantId(), c.secretHash(),
                c.secretHint(), c.status().name(), c.createdAt(), c.createdBy(), c.expiresAt(), c.lastUsedAt(),
                c.revokedAt(), c.revokedBy(), c.revokeReason() == null ? null : c.revokeReason().name()));
        return c;
    }

    @Override
    public List<ApplicationCredential> findByApplication(UUID applicationId) {
        return jpa.findByApplication(applicationId).stream().map(ApplicationCredentialPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public List<ApplicationCredential> findByApplications(Collection<UUID> applicationIds) {
        if (applicationIds.isEmpty()) return List.of();
        return jpa.findByApplications(applicationIds).stream().map(ApplicationCredentialPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public Optional<ApplicationCredential> findByIdAndApplication(UUID credentialId, UUID applicationId) {
        return jpa.findByIdAndApplication(credentialId, applicationId)
                .map(ApplicationCredentialPersistenceAdapter::toDomain);
    }

    private static ApplicationCredential toDomain(ApplicationCredentialJpaEntity e) {
        return ApplicationCredential.restore(e.getId(), e.getApplicationId(), e.getTenantId(), e.getSecretHash(),
                e.getSecretHint(), CredentialStatus.valueOf(e.getStatus()), e.getCreatedAt(), e.getCreatedBy(),
                e.getExpiresAt(), e.getLastUsedAt(), e.getRevokedAt(), e.getRevokedBy(),
                e.getRevokeReason() == null ? null : RevokeReason.valueOf(e.getRevokeReason()));
    }
}

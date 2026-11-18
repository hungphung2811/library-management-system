package lms.identity.apiclient.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import lms.identity.apiclient.domain.ApplicationHistoryAction;
import lms.identity.apiclient.domain.ApplicationHistoryEntry;
import lms.identity.apiclient.domain.ApplicationHistoryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

/** Lịch sử chỉ thêm: chỉ INSERT (UPDATE/DELETE bị trigger và REVOKE chặn). */
@Repository
class ApplicationHistoryPersistenceAdapter implements ApplicationHistoryRepository {

    private final SpringDataApplicationHistoryRepository jpa;

    ApplicationHistoryPersistenceAdapter(SpringDataApplicationHistoryRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public void append(ApplicationHistoryEntry e) {
        jpa.saveAndFlush(new ApplicationHistoryJpaEntity(e.id(), e.tenantId(), e.applicationId(), e.credentialId(),
                e.action().name(), e.detail(), e.actorId(), e.occurredAt()));
    }

    @Override
    public List<ApplicationHistoryEntry> findByApplication(UUID tenantId, UUID applicationId, int limit) {
        return jpa.findByApplication(tenantId, applicationId, PageRequest.of(0, limit)).stream()
                .map(h -> new ApplicationHistoryEntry(h.getId(), h.getTenantId(), h.getApplicationId(),
                        h.getCredentialId(), ApplicationHistoryAction.valueOf(h.getAction()), h.getDetail(),
                        h.getActorId(), h.getOccurredAt()))
                .toList();
    }
}

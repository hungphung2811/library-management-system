package lms.support.audit.infrastructure.persistence;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import lms.support.audit.application.SaveAuditLogPort;
import lms.support.audit.domain.AuditLog;
import lms.support.audit.domain.AuditLogCriteria;
import lms.support.audit.domain.AuditLogPage;
import lms.support.audit.domain.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/** Adapter JPA cho AUDIT_DB (EntityManagerFactory/TransactionManager lấy từ database-common). */
public class AuditLogPersistenceAdapter implements AuditLogRepository, SaveAuditLogPort {

    private final SpringDataAuditLogRepository jpa;

    public AuditLogPersistenceAdapter(SpringDataAuditLogRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean saveIfAbsent(AuditLog log) {
        return jpa.insertIfAbsent(log.id(), log.eventId(), log.eventType(), log.aggregateId(), log.occurredAt(),
                log.recordedAt(), log.payload()) == 1;
    }

    @Override
    public AuditLogPage search(AuditLogCriteria criteria) {
        Specification<AuditLogJpaEntity> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (criteria.aggregateId() != null) {
                predicates.add(cb.equal(root.get("aggregateId"), criteria.aggregateId()));
            }
            if (criteria.eventType() != null) {
                predicates.add(cb.equal(root.get("eventType"), criteria.eventType()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<AuditLogJpaEntity> page = jpa.findAll(spec, PageRequest.of(criteria.page(), criteria.size(),
                Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by("id"))));
        return new AuditLogPage(page.getContent().stream().map(AuditLogPersistenceAdapter::toDomain).toList(),
                page.getTotalElements(), criteria.page(), criteria.size());
    }

    private static AuditLog toDomain(AuditLogJpaEntity e) {
        return new AuditLog(e.getId(), e.getEventId(), e.getEventType(), e.getAggregateId(), e.getOccurredAt(),
                e.getRecordedAt(), e.getPayload());
    }
}

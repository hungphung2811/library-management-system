package lms.sales.shared.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.application.SalesEventEnrichmentPort;
import lms.sales.shared.infrastructure.messaging.SalesEvent;
import lms.sales.shared.infrastructure.messaging.SalesEventPublisher;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi sales_audit_log trong transaction của thao tác nghiệp vụ (rollback thì mất cả hai), rồi đăng ký phát
 * sales.events sau commit. Bắt buộc có transaction đang mở (MANDATORY).
 */
@Repository
class SalesAuditAdapter implements SalesAuditPort {

    @PersistenceContext
    private EntityManager em;

    private final SalesEventPublisher publisher;
    private final SalesEventEnrichmentPort enrichment;

    SalesAuditAdapter(SalesEventPublisher publisher, SalesEventEnrichmentPort enrichment) {
        this.publisher = publisher;
        this.enrichment = enrichment;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        UUID id = UUID.randomUUID();
        em.persist(new SalesAuditJpaEntity(id, entry.action(), entry.aggregateType(), entry.aggregateId(),
                entry.actorId(), entry.occurredAt(), entry.data()));
        publisher.publishAfterCommit(SalesEvent.of(id, withEnrichment(entry)));
    }

    /**
     * ADR-0030 gói D: trường làm giàu chỉ vào sự kiện, không vào dòng sales_audit_log (đã persist bằng entry gốc).
     * Khóa đã có trong audit được giữ nguyên.
     */
    private SalesAuditPort.AuditEntry withEnrichment(SalesAuditPort.AuditEntry entry) {
        Map<String, Object> extra = enrichment.enrich(entry);
        if (extra.isEmpty()) {
            return entry;
        }
        Map<String, Object> merged = new LinkedHashMap<>(entry.data());
        extra.forEach(merged::putIfAbsent);
        return new SalesAuditPort.AuditEntry(entry.tenantId(), entry.action(), entry.aggregateType(),
                entry.aggregateId(), entry.actorId(), entry.occurredAt(), merged);
    }
}

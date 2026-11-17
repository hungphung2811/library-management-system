package lms.sales.shared.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.application.SalesAuditQueryPort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Đọc sales_audit_log (nguồn chuẩn của nhật ký bán hàng), mới nhất trước. */
@Repository
class SalesAuditQueryAdapter implements SalesAuditQueryPort {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional(readOnly = true)
    public PageResult<Entry> search(UUID tenantId, Criteria c, int page, int size) {
        String where = "from SalesAuditJpaEntity a where a.tenantId = :t"
                + (c.action() != null ? " and a.action = :action" : "")
                + (c.aggregateType() != null ? " and a.aggregateType = :atype" : "")
                + (c.aggregateId() != null ? " and a.aggregateId = :aid" : "")
                + (c.from() != null ? " and a.occurredAt >= :from" : "")
                + (c.to() != null ? " and a.occurredAt < :to" : "");
        var count = em.createQuery("select count(a) " + where, Long.class);
        var rows = em.createQuery("select a " + where + " order by a.occurredAt desc, a.id desc",
                SalesAuditJpaEntity.class);
        for (var q : List.of(count, rows)) {
            q.setParameter("t", tenantId);
            if (c.action() != null) q.setParameter("action", c.action());
            if (c.aggregateType() != null) q.setParameter("atype", c.aggregateType());
            if (c.aggregateId() != null) q.setParameter("aid", c.aggregateId());
            if (c.from() != null) q.setParameter("from", c.from());
            if (c.to() != null) q.setParameter("to", c.to());
        }
        List<Entry> items = rows.setFirstResult(page * size).setMaxResults(size).getResultList().stream()
                .map(a -> new Entry(a.getId(), a.getAction(), a.getAggregateType(), a.getAggregateId(),
                        a.getActorId(), a.getOccurredAt(), a.getData() == null ? java.util.Map.of() : a.getData()))
                .toList();
        return new PageResult<>(items, count.getSingleResult(), page, size);
    }
}

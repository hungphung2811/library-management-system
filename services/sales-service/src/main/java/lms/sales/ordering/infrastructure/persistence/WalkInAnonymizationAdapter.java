package lms.sales.ordering.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.sales.ordering.application.WalkInAnonymizationPort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ẩn danh bằng JPQL bulk update có điều kiện tenant tường minh (RLS vẫn chặn ở DB). Điều kiện "chưa ẩn danh" nằm
 * trong câu UPDATE nên chạy lại hoặc hai instance chạy chồng chỉ cập nhật 0 dòng. Tăng version để khóa lạc quan của
 * đơn không bỏ sót thay đổi này.
 */
@Repository
class WalkInAnonymizationAdapter implements WalkInAnonymizationPort {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findDue(UUID tenantId, Instant cutoff, int limit) {
        return em.createQuery("select o.id from SalesOrderJpaEntity o where o.tenantId = :t "
                        + "and o.piiAnonymizedAt is null and (o.walkInName is not null or o.walkInPhone is not null) "
                        + "and ((o.status = 'COMPLETED' and o.completedAt < :cutoff) "
                        + "  or (o.status = 'CANCELLED' and o.cancelledAt < :cutoff)) order by o.createdAt, o.id",
                        UUID.class)
                .setParameter("t", tenantId).setParameter("cutoff", cutoff).setMaxResults(limit).getResultList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int anonymize(UUID tenantId, List<UUID> orderIds, Instant now) {
        if (orderIds.isEmpty()) {
            return 0;
        }
        return em.createQuery("update SalesOrderJpaEntity o set o.walkInName = null, o.walkInPhone = null, "
                        + "o.piiAnonymizedAt = :now, o.version = o.version + 1 "
                        + "where o.tenantId = :t and o.id in :ids and o.piiAnonymizedAt is null")
                .setParameter("now", now).setParameter("t", tenantId).setParameter("ids", orderIds)
                .executeUpdate();
    }
}

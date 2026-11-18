package lms.sales.shift.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.shared.infrastructure.persistence.PersistenceErrors;
import lms.sales.shift.application.CashierShiftRepository;
import lms.sales.shift.domain.CashierShift;
import lms.sales.shift.domain.ShiftEvent;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Mọi truy vấn là JPQL (qua bộ lọc @TenantId) cộng RLS ở DB; không native query. */
@Repository
class CashierShiftPersistenceAdapter implements CashierShiftRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public CashierShift save(CashierShift s) {
        try {
            CashierShiftJpaEntity entity = em.find(CashierShiftJpaEntity.class, s.id());
            if (entity == null) {
                entity = new CashierShiftJpaEntity(s);
                em.persist(entity);
            } else {
                if (entity.getVersion() != s.version()) {
                    throw conflict();
                }
                entity.apply(s);
            }
            em.flush();
            return entity.toDomain();
        } catch (PersistenceException e) {
            if (PersistenceErrors.isOptimisticLock(e)) {
                throw conflict();
            }
            if (PersistenceErrors.isUniqueViolation(e, "uq_cashier_shifts_open", "uq_cashier_shifts_request")) {
                throw new SalesException(ErrorCode.SHIFT_ALREADY_OPEN, "Thu ngân đang có một ca mở");
            }
            throw e;
        }
    }

    private static SalesException conflict() {
        return new SalesException(ErrorCode.SHIFT_STATE_CONFLICT, "Ca đã bị thay đổi bởi thao tác khác");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CashierShift> findById(UUID tenantId, UUID id) {
        return em.createQuery("select s from CashierShiftJpaEntity s where s.tenantId = :t and s.id = :id",
                        CashierShiftJpaEntity.class)
                .setParameter("t", tenantId).setParameter("id", id).getResultList().stream().findFirst()
                .map(CashierShiftJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CashierShift> findOpenByCashier(UUID tenantId, UUID cashierUserId) {
        return em.createQuery("select s from CashierShiftJpaEntity s where s.tenantId = :t "
                                + "and s.cashierUserId = :c and s.status = 'OPEN'", CashierShiftJpaEntity.class)
                .setParameter("t", tenantId).setParameter("c", cashierUserId).getResultList().stream().findFirst()
                .map(CashierShiftJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CashierShift> findByClientRequestId(UUID tenantId, UUID clientRequestId) {
        return em.createQuery("select s from CashierShiftJpaEntity s where s.tenantId = :t "
                                + "and s.clientRequestId = :r", CashierShiftJpaEntity.class)
                .setParameter("t", tenantId).setParameter("r", clientRequestId).getResultList().stream().findFirst()
                .map(CashierShiftJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<CashierShift> search(UUID tenantId, Criteria c, int page, int size) {
        StringBuilder where = new StringBuilder("from CashierShiftJpaEntity s where s.tenantId = :t");
        if (c.cashierUserId() != null) {
            where.append(" and s.cashierUserId = :cashier");
        }
        if (c.status() != null) {
            where.append(" and s.status = :status");
        }
        if (c.varianceStatus() != null) {
            where.append(" and s.varianceStatus = :vs");
        }
        if (c.from() != null) {
            where.append(" and s.openedAt >= :from");
        }
        if (c.to() != null) {
            where.append(" and s.openedAt < :to");
        }
        var count = em.createQuery("select count(s) " + where, Long.class);
        var rows = em.createQuery("select s " + where + " order by s.openedAt desc, s.id desc",
                CashierShiftJpaEntity.class);
        for (var q : List.of(count, rows)) {
            q.setParameter("t", tenantId);
            if (c.cashierUserId() != null) {
                q.setParameter("cashier", c.cashierUserId());
            }
            if (c.status() != null) {
                q.setParameter("status", c.status().name());
            }
            if (c.varianceStatus() != null) {
                q.setParameter("vs", c.varianceStatus().name());
            }
            if (c.from() != null) {
                q.setParameter("from", c.from());
            }
            if (c.to() != null) {
                q.setParameter("to", c.to());
            }
        }
        List<CashierShift> items = rows.setFirstResult(page * size).setMaxResults(size).getResultList().stream()
                .map(CashierShiftJpaEntity::toDomain).toList();
        return new PageResult<>(items, count.getSingleResult(), page, size);
    }

    @Override
    @Transactional
    public void appendEvent(UUID tenantId, ShiftEvent event) {
        em.persist(new CashierShiftEventJpaEntity(event));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShiftEvent> events(UUID tenantId, UUID shiftId) {
        return em.createQuery("select e from CashierShiftEventJpaEntity e where e.tenantId = :t "
                                + "and e.shiftId = :s order by e.occurredAt, e.id", CashierShiftEventJpaEntity.class)
                .setParameter("t", tenantId).setParameter("s", shiftId).getResultList().stream()
                .map(CashierShiftEventJpaEntity::toDomain).toList();
    }
}

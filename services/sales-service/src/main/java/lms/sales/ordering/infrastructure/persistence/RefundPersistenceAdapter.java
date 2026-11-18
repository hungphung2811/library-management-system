package lms.sales.ordering.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.sales.ordering.application.RefundRepository;
import lms.sales.ordering.domain.PaymentMethod;
import lms.sales.ordering.domain.Refund;
import lms.sales.ordering.domain.RefundSource;
import lms.sales.ordering.domain.RefundStatus;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import lms.sales.shared.infrastructure.persistence.PersistenceErrors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class RefundPersistenceAdapter implements RefundRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public Refund save(Refund r) {
        try {
            RefundJpaEntity entity = em.find(RefundJpaEntity.class, r.id());
            if (entity == null) {
                em.persist(new RefundJpaEntity(r));
            } else {
                if (entity.getVersion() != r.version()) {
                    throw conflict();
                }
                entity.apply(r);
            }
            em.flush();
            return toDomain(em.find(RefundJpaEntity.class, r.id()));
        } catch (PersistenceException e) {
            if (PersistenceErrors.isOptimisticLock(e)) {
                throw conflict();
            }
            if (PersistenceErrors.isUniqueViolation(e, "uq_refunds_cancellation", "uq_refunds_return")) {
                throw new SalesException(ErrorCode.ORDER_STATE_CONFLICT, "Khoản hoàn đã tồn tại");
            }
            throw e;
        }
    }

    private static SalesException conflict() {
        return new SalesException(ErrorCode.ORDER_STATE_CONFLICT, "Khoản hoàn đã bị thay đổi bởi thao tác khác");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Refund> findById(UUID tenantId, UUID id) {
        return em.createQuery("select r from RefundJpaEntity r where r.tenantId = :t and r.id = :id",
                        RefundJpaEntity.class).setParameter("t", tenantId).setParameter("id", id)
                .getResultList().stream().findFirst().map(RefundPersistenceAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<Refund> search(UUID tenantId, RefundStatus status, int page, int size) {
        String where = "from RefundJpaEntity r where r.tenantId = :t" + (status != null ? " and r.status = :s" : "");
        var count = em.createQuery("select count(r) " + where, Long.class);
        var rows = em.createQuery("select r " + where + " order by r.createdAt desc, r.id desc",
                RefundJpaEntity.class);
        for (var q : List.of(count, rows)) {
            q.setParameter("t", tenantId);
            if (status != null) {
                q.setParameter("s", status.name());
            }
        }
        List<Refund> items = rows.setFirstResult(page * size).setMaxResults(size).getResultList().stream()
                .map(RefundPersistenceAdapter::toDomain).toList();
        return new PageResult<>(items, count.getSingleResult(), page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Refund> findByOrderId(UUID tenantId, UUID orderId) {
        return em.createQuery("select r from RefundJpaEntity r where r.tenantId = :t and r.orderId = :o "
                        + "order by r.createdAt, r.id", RefundJpaEntity.class)
                .setParameter("t", tenantId).setParameter("o", orderId).getResultList().stream()
                .map(RefundPersistenceAdapter::toDomain).toList();
    }

    private static Refund toDomain(RefundJpaEntity e) {
        return Refund.restore(e.getId(), e.getTenantId(), e.getOrderId(), RefundSource.valueOf(e.getSource()),
                e.getReturnId(), Money.of(e.getAmount()), RefundStatus.valueOf(e.getStatus()),
                e.getMethod() == null ? null : PaymentMethod.valueOf(e.getMethod()), e.getReference(),
                e.getCreatedAt(), e.getRefundedBy(), e.getRefundedAt(), e.getShiftId(), e.getVersion());
    }
}

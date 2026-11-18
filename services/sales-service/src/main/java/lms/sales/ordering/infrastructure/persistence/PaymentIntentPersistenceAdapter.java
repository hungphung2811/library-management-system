package lms.sales.ordering.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.sales.ordering.application.PaymentIntentRepository;
import lms.sales.ordering.domain.PaymentIntent;
import lms.sales.ordering.domain.PaymentIntentStatus;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import lms.sales.shared.infrastructure.persistence.PersistenceErrors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Mọi truy vấn là JPQL (qua bộ lọc @TenantId) cộng RLS ở DB; không native query. */
@Repository
class PaymentIntentPersistenceAdapter implements PaymentIntentRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public PaymentIntent save(PaymentIntent i) {
        try {
            PaymentIntentJpaEntity entity = em.find(PaymentIntentJpaEntity.class, i.id());
            if (entity == null) {
                entity = new PaymentIntentJpaEntity(i);
                em.persist(entity);
            } else {
                if (entity.getVersion() != i.version()) {
                    throw conflict();
                }
                entity.apply(i);
            }
            em.flush();
            return toDomain(entity);
        } catch (PersistenceException e) {
            if (PersistenceErrors.isUniqueViolation(e, "uq_payment_intents_txn")) {
                throw new SalesException(ErrorCode.PAYMENT_DUPLICATE_TRANSACTION,
                        "Mã giao dịch của cổng thanh toán đã được dùng");
            }
            if (PersistenceErrors.isUniqueViolation(e, "uq_payment_intents_reference")) {
                throw new SalesException(ErrorCode.PAYMENT_INTENT_STATE_CONFLICT,
                        "Trùng mã tham chiếu thanh toán, hãy thử lại");
            }
            if (PersistenceErrors.isOptimisticLock(e)) {
                throw conflict();
            }
            throw e;
        }
    }

    private static SalesException conflict() {
        return new SalesException(ErrorCode.PAYMENT_INTENT_STATE_CONFLICT,
                "Lệnh thanh toán đã bị thay đổi bởi thao tác khác");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentIntent> findById(UUID tenantId, UUID id) {
        return em.createQuery("select i from PaymentIntentJpaEntity i where i.tenantId = :t and i.id = :id",
                        PaymentIntentJpaEntity.class)
                .setParameter("t", tenantId).setParameter("id", id).getResultList().stream().findFirst()
                .map(PaymentIntentPersistenceAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentIntent> findByReference(UUID tenantId, String reference) {
        return em.createQuery("select i from PaymentIntentJpaEntity i where i.tenantId = :t "
                                + "and i.reference = :r", PaymentIntentJpaEntity.class)
                .setParameter("t", tenantId).setParameter("r", reference).getResultList().stream().findFirst()
                .map(PaymentIntentPersistenceAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PaymentIntent> findByOrderId(UUID tenantId, UUID orderId) {
        return em.createQuery("select i from PaymentIntentJpaEntity i where i.tenantId = :t "
                                + "and i.orderId = :o order by i.createdAt desc", PaymentIntentJpaEntity.class)
                .setParameter("t", tenantId).setParameter("o", orderId).setMaxResults(1).getResultList().stream()
                .findFirst().map(PaymentIntentPersistenceAdapter::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findExpiredPendingIds(UUID tenantId, Instant now, int limit) {
        return em.createQuery("select i.id from PaymentIntentJpaEntity i where i.tenantId = :t "
                        + "and i.status = 'PENDING' and i.expiresAt <= :now order by i.expiresAt", UUID.class)
                .setParameter("t", tenantId).setParameter("now", now).setMaxResults(limit).getResultList();
    }

    private static PaymentIntent toDomain(PaymentIntentJpaEntity e) {
        return PaymentIntent.restore(new PaymentIntent.Snapshot(e.getId(), e.getTenantId(), e.getOrderId(),
                Money.of(e.getAmount()), e.getReference(), PaymentIntentStatus.valueOf(e.getStatus()),
                e.getProvider(), e.getProviderTxnId(), e.getQrPayload(), e.getExpiresAt(), e.getPaidAt(),
                e.getCreatedAt(), e.getVersion(), e.getTenderedAmount(), e.getChangeDue(), e.getTransactionRef()));
    }
}

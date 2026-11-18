package lms.sales.ordering.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Truy vấn chỉ-đọc của {@link PaymentTenantResolverAdapter}, tách bean để @Transactional có hiệu lực (qua proxy). */
@Component
class PaymentTenantQuery {

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    Optional<UUID> tenantOf(String reference) {
        return em.createQuery("select i.tenantId from PaymentIntentJpaEntity i where i.reference = :r", UUID.class)
                .setParameter("r", reference).setMaxResults(1).getResultList().stream().findFirst();
    }
}

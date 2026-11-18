package lms.sales.invoicing.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.util.Optional;
import java.util.UUID;
import lms.sales.invoicing.application.InvoiceRequestRepository;
import lms.sales.invoicing.domain.InvoiceRequest;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.shared.infrastructure.persistence.PersistenceErrors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Lưu yêu cầu xuất hóa đơn: chèn mới hoặc cập nhật theo khóa lạc quan. Hai bên tạo cùng lúc thì bên thua nhận 409. */
@Repository
class InvoiceRequestPersistenceAdapter implements InvoiceRequestRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional(readOnly = true)
    public Optional<InvoiceRequest> findByOrder(UUID tenantId, UUID orderId) {
        return find(tenantId, orderId).map(InvoiceRequestJpaEntity::toDomain);
    }

    @Override
    @Transactional
    public InvoiceRequest save(InvoiceRequest request) {
        try {
            var existing = em.find(InvoiceRequestJpaEntity.class, request.id());
            if (existing == null) {
                var created = new InvoiceRequestJpaEntity(request);
                em.persist(created);
                em.flush();
                return created.toDomain();
            }
            if (existing.getVersion() != request.version()) {
                throw conflict();
            }
            existing.apply(request);
            em.flush();
            return existing.toDomain();
        } catch (PersistenceException e) {
            if (PersistenceErrors.isUniqueViolation(e, "uq_invoice_requests_order")
                    || PersistenceErrors.isOptimisticLock(e)) {
                throw conflict();
            }
            throw e;
        }
    }

    private Optional<InvoiceRequestJpaEntity> find(UUID tenantId, UUID orderId) {
        return em.createQuery("select r from InvoiceRequestJpaEntity r where r.tenantId = :t and r.orderId = :o",
                        InvoiceRequestJpaEntity.class).setParameter("t", tenantId).setParameter("o", orderId)
                .getResultList().stream().findFirst();
    }

    private static SalesException conflict() {
        return new SalesException(ErrorCode.INVOICE_STATE_CONFLICT, "Yêu cầu xuất hóa đơn đã được thay đổi bởi thao tác khác");
    }
}

package lms.sales.invoicing.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.sales.invoicing.application.InvoiceRepository;
import lms.sales.invoicing.application.InvoiceSummary;
import lms.sales.invoicing.domain.Invoice;
import lms.sales.invoicing.domain.InvoiceLine;
import lms.sales.invoicing.domain.InvoiceNumbers;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.shared.infrastructure.persistence.Like;
import lms.sales.shared.infrastructure.persistence.PersistenceErrors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lưu hóa đơn bằng JPA. Chèn: hóa đơn rồi các dòng, một lần flush; vi phạm chỉ mục duy nhất được dịch sang mã lỗi nghiệp
 * vụ. Cập nhật chỉ ghi nhóm cột được phép đổi sau phát hành ({@code InvoiceJpaEntity#applyMutable}) kèm kiểm khóa lạc quan.
 * Truy vấn danh sách không nạp dòng; truy vấn nhiều hóa đơn nạp dòng bằng một câu {@code IN}.
 */
@Repository
class InvoicePersistenceAdapter implements InvoiceRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public Invoice insert(Invoice invoice) {
        try {
            em.persist(new InvoiceJpaEntity(invoice));
            for (InvoiceLine l : invoice.lines()) {
                em.persist(new InvoiceLineJpaEntity(invoice.id(), l));
            }
            em.flush();
            return invoice;
        } catch (PersistenceException e) {
            if (PersistenceErrors.isUniqueViolation(e, "uq_invoices_order_effective", "uq_invoices_return")) {
                throw new SalesException(ErrorCode.INVOICE_ALREADY_EXISTS, "Đơn đã có hóa đơn còn hiệu lực");
            }
            if (PersistenceErrors.isUniqueViolation(e, "uq_invoices_client_request")) {
                throw new SalesException(ErrorCode.CLIENT_REQUEST_CONFLICT, "clientRequestId đã được dùng");
            }
            throw e;
        }
    }

    @Override
    @Transactional
    public Invoice update(Invoice invoice) {
        InvoiceJpaEntity e = em.createQuery("select i from InvoiceJpaEntity i where i.tenantId = :t and i.id = :id",
                        InvoiceJpaEntity.class).setParameter("t", invoice.tenantId()).setParameter("id", invoice.id())
                .getResultList().stream().findFirst()
                .orElseThrow(() -> new SalesException(ErrorCode.INVOICE_NOT_FOUND, "Không có hóa đơn"));
        if (e.getVersion() != invoice.version()) {
            throw new SalesException(ErrorCode.INVOICE_STATE_CONFLICT, "Hóa đơn đã bị thay đổi bởi thao tác khác");
        }
        e.applyMutable(invoice);
        try {
            em.flush();
        } catch (PersistenceException ex) {
            if (PersistenceErrors.isOptimisticLock(ex)) {
                throw new SalesException(ErrorCode.INVOICE_STATE_CONFLICT, "Hóa đơn đã bị thay đổi bởi thao tác khác");
            }
            throw ex;
        }
        return assemble(List.of(e)).get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Invoice> findById(UUID tenantId, UUID id) {
        return em.createQuery("select i from InvoiceJpaEntity i where i.tenantId = :t and i.id = :id",
                        InvoiceJpaEntity.class).setParameter("t", tenantId).setParameter("id", id)
                .getResultList().stream().findFirst().map(e -> assemble(List.of(e)).get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Invoice> findByClientRequestId(UUID tenantId, UUID clientRequestId) {
        return em.createQuery("select i from InvoiceJpaEntity i where i.tenantId = :t "
                                + "and i.clientRequestId = :c", InvoiceJpaEntity.class)
                .setParameter("t", tenantId).setParameter("c", clientRequestId)
                .getResultList().stream().findFirst().map(e -> assemble(List.of(e)).get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Invoice> findEffectiveForOrder(UUID tenantId, UUID orderId) {
        return em.createQuery("select i from InvoiceJpaEntity i where i.tenantId = :t and i.orderId = :o "
                                + "and i.kind in ('ORIGINAL','REPLACEMENT') and i.status = 'ISSUED'",
                        InvoiceJpaEntity.class).setParameter("t", tenantId).setParameter("o", orderId)
                .getResultList().stream().findFirst().map(e -> assemble(List.of(e)).get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Invoice> findActiveAdjustments(UUID tenantId, UUID originalInvoiceId) {
        return assemble(em.createQuery("select i from InvoiceJpaEntity i where i.tenantId = :t "
                                + "and i.originalInvoiceId = :o and i.kind = 'ADJUSTMENT_DECREASE' "
                                + "and i.status = 'ISSUED' order by i.createdAt, i.id", InvoiceJpaEntity.class)
                .setParameter("t", tenantId).setParameter("o", originalInvoiceId).getResultList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Invoice> findAdjustmentByReturn(UUID tenantId, UUID returnId) {
        return em.createQuery("select i from InvoiceJpaEntity i where i.tenantId = :t and i.returnId = :r "
                                + "and i.status <> 'CANCELLED'", InvoiceJpaEntity.class)
                .setParameter("t", tenantId).setParameter("r", returnId)
                .getResultList().stream().findFirst().map(e -> assemble(List.of(e)).get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<InvoiceSummary> search(UUID tenantId, Criteria c, int page, int size) {
        StringBuilder where = new StringBuilder(" where i.tenantId = :t");
        Map<String, Object> params = new HashMap<>();
        params.put("t", tenantId);
        if (c.status() != null) {
            where.append(" and i.status = :st");
            params.put("st", c.status().name());
        }
        if (c.kind() != null) {
            where.append(" and i.kind = :kd");
            params.put("kd", c.kind().name());
        }
        if (c.from() != null) {
            where.append(" and i.createdAt >= :from");
            params.put("from", c.from());
        }
        if (c.to() != null) {
            where.append(" and i.createdAt < :to");
            params.put("to", c.to());
        }
        if (c.keyword() != null) {
            String like = "%" + Like.escape(c.keyword().toLowerCase()) + "%";
            where.append(" and (lower(i.orderNo) like :kw escape '\\' or lower(i.buyerName) like :kw escape '\\' "
                    + "or lower(i.buyerCompany) like :kw escape '\\' or i.buyerTaxCode like :kw escape '\\'");
            params.put("kw", like);
            if (c.keyword().chars().allMatch(Character::isDigit) && c.keyword().length() <= 18) {
                where.append(" or i.invoiceNo = :no");
                params.put("no", Long.parseLong(c.keyword()));
            }
            where.append(")");
        }
        TypedQuery<Long> count = em.createQuery("select count(i) from InvoiceJpaEntity i" + where, Long.class);
        TypedQuery<InvoiceJpaEntity> data = em.createQuery("select i from InvoiceJpaEntity i" + where
                + " order by i.createdAt desc, i.id", InvoiceJpaEntity.class);
        params.forEach((k, v) -> {
            count.setParameter(k, v);
            data.setParameter(k, v);
        });
        data.setFirstResult(page * size).setMaxResults(size);
        List<InvoiceSummary> items = data.getResultList().stream().map(InvoicePersistenceAdapter::summary).toList();
        return new PageResult<>(items, count.getSingleResult(), page, size);
    }

    private static InvoiceSummary summary(InvoiceJpaEntity e) {
        return new InvoiceSummary(e.getId(), e.getKind(), e.getStatus(), e.getOrderId(), e.getOrderNo(),
                e.getOriginalInvoiceId(), e.getTemplateCode(), e.getSeries(), e.getFiscalYear(), e.getInvoiceNo(),
                e.getInvoiceNo() == null ? null : InvoiceNumbers.display(e.getInvoiceNo()), e.getBuyerName(),
                e.getBuyerCompany(), e.getBuyerTaxCode(), e.getGrandTotal(), e.getIssuedAt(), e.getCreatedAt());
    }

    /** Ghép dòng: một câu IN cho mọi hóa đơn (không N+1). */
    private List<Invoice> assemble(List<InvoiceJpaEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = entities.stream().map(InvoiceJpaEntity::getId).toList();
        Map<UUID, List<InvoiceLineJpaEntity>> byInvoice = new HashMap<>();
        for (InvoiceLineJpaEntity l : em.createQuery("select l from InvoiceLineJpaEntity l where l.invoiceId in :ids",
                InvoiceLineJpaEntity.class).setParameter("ids", ids).getResultList()) {
            byInvoice.computeIfAbsent(l.getInvoiceId(), k -> new ArrayList<>()).add(l);
        }
        return entities.stream().map(e -> e.toDomain(byInvoice.getOrDefault(e.getId(), List.of()).stream()
                .sorted(Comparator.comparingInt(InvoiceLineJpaEntity::getLineNo))
                .map(InvoiceLineJpaEntity::toDomain).toList())).toList();
    }
}

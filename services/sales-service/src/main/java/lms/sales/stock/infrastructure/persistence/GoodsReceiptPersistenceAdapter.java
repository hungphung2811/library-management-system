package lms.sales.stock.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.domain.DuplicateClientRequestException;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import lms.sales.shared.infrastructure.persistence.PersistenceErrors;
import lms.sales.stock.application.GoodsReceiptRepository;
import lms.sales.stock.domain.GoodsReceipt;
import lms.sales.stock.domain.GoodsReceiptLine;
import lms.sales.stock.domain.ReceiptStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class GoodsReceiptPersistenceAdapter implements GoodsReceiptRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public GoodsReceipt save(GoodsReceipt r) {
        try {
            GoodsReceiptJpaEntity entity = em.find(GoodsReceiptJpaEntity.class, r.id());
            if (entity == null) {
                entity = new GoodsReceiptJpaEntity(r.id(), r.receiptNo(), r.supplierId(), r.status().name(),
                        r.clientRequestId(), r.createdBy(), r.createdAt());
                em.persist(entity);
                int lineNo = 1;
                for (GoodsReceiptLine l : r.lines()) {
                    em.persist(new GoodsReceiptLineJpaEntity(r.id(), lineNo++, l.titleId(), l.quantity(),
                            l.unitCost().amount()));
                }
            } else {
                if (entity.getVersion() != r.version()) {
                    throw conflict();
                }
                entity.updateState(r.status().name(), r.confirmedAt(), r.confirmedBy());
            }
            em.flush();
            return toDomain(entity, linesOf(List.of(entity.getId())).getOrDefault(entity.getId(), List.of()));
        } catch (PersistenceException e) {
            if (PersistenceErrors.isUniqueViolation(e, "uq_goods_receipts_tenant_request")) {
                throw new DuplicateClientRequestException();
            }
            if (PersistenceErrors.isOptimisticLock(e)) {
                throw conflict();
            }
            throw e;
        }
    }

    private static SalesException conflict() {
        return new SalesException(ErrorCode.RECEIPT_STATE_CONFLICT, "Phiếu nhập đã bị thay đổi bởi thao tác khác");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GoodsReceipt> findById(UUID tenantId, UUID id) {
        return em.createQuery("select r from GoodsReceiptJpaEntity r where r.tenantId = :t and r.id = :id",
                        GoodsReceiptJpaEntity.class).setParameter("t", tenantId).setParameter("id", id)
                .getResultList().stream().findFirst().map(this::withLines);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GoodsReceipt> findByClientRequestId(UUID tenantId, UUID clientRequestId) {
        return em.createQuery("select r from GoodsReceiptJpaEntity r where r.tenantId = :t "
                                + "and r.clientRequestId = :c", GoodsReceiptJpaEntity.class)
                .setParameter("t", tenantId).setParameter("c", clientRequestId)
                .getResultList().stream().findFirst().map(this::withLines);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<GoodsReceipt> search(UUID tenantId, ReceiptStatus status, UUID supplierId, int page, int size) {
        String where = "from GoodsReceiptJpaEntity r where r.tenantId = :t"
                + (status != null ? " and r.status = :status" : "")
                + (supplierId != null ? " and r.supplierId = :supplier" : "");
        var count = em.createQuery("select count(r) " + where, Long.class).setParameter("t", tenantId);
        var rows = em.createQuery("select r " + where + " order by r.createdAt desc, r.id desc",
                GoodsReceiptJpaEntity.class).setParameter("t", tenantId);
        if (status != null) {
            count.setParameter("status", status.name());
            rows.setParameter("status", status.name());
        }
        if (supplierId != null) {
            count.setParameter("supplier", supplierId);
            rows.setParameter("supplier", supplierId);
        }
        List<GoodsReceiptJpaEntity> page1 = rows.setFirstResult(page * size).setMaxResults(size).getResultList();
        Map<UUID, List<GoodsReceiptLine>> lines = linesOf(page1.stream().map(GoodsReceiptJpaEntity::getId).toList());
        List<GoodsReceipt> items = page1.stream()
                .map(e -> toDomain(e, lines.getOrDefault(e.getId(), List.of()))).toList();
        return new PageResult<>(items, count.getSingleResult(), page, size);
    }

    private GoodsReceipt withLines(GoodsReceiptJpaEntity e) {
        return toDomain(e, linesOf(List.of(e.getId())).getOrDefault(e.getId(), List.of()));
    }

    private Map<UUID, List<GoodsReceiptLine>> linesOf(List<UUID> receiptIds) {
        Map<UUID, List<GoodsReceiptLine>> out = new HashMap<>();
        if (receiptIds.isEmpty()) {
            return out;
        }
        List<GoodsReceiptLineJpaEntity> rows = em.createQuery("select l from GoodsReceiptLineJpaEntity l "
                        + "where l.receiptId in :ids", GoodsReceiptLineJpaEntity.class)
                .setParameter("ids", receiptIds).getResultList();
        rows.stream().sorted(Comparator.comparingInt(GoodsReceiptLineJpaEntity::getLineNo)).forEach(l ->
                out.computeIfAbsent(l.getReceiptId(), k -> new ArrayList<>()).add(
                        new GoodsReceiptLine(l.getTitleId(), l.getQuantity(), Money.of(l.getUnitCost()))));
        return out;
    }

    private static GoodsReceipt toDomain(GoodsReceiptJpaEntity e, List<GoodsReceiptLine> lines) {
        return GoodsReceipt.restore(e.getId(), e.getTenantId(), e.getReceiptNo(), e.getSupplierId(), lines,
                e.getClientRequestId(), e.getCreatedBy(), e.getCreatedAt(), ReceiptStatus.valueOf(e.getStatus()),
                e.getConfirmedAt(), e.getConfirmedBy(), e.getVersion());
    }
}

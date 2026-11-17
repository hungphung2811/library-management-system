package lms.sales.ordering.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.sales.ordering.application.SalesReturnRepository;
import lms.sales.ordering.domain.Disposition;
import lms.sales.ordering.domain.ReturnLine;
import lms.sales.ordering.domain.ReturnPlanner;
import lms.sales.ordering.domain.SalesReturn;
import lms.sales.shared.domain.DuplicateClientRequestException;
import lms.sales.shared.domain.Money;
import lms.sales.shared.infrastructure.persistence.PersistenceErrors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class SalesReturnPersistenceAdapter implements SalesReturnRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional
    public SalesReturn save(SalesReturn r) {
        try {
            em.persist(new SalesReturnJpaEntity(r));
            for (ReturnLine l : r.lines()) {
                em.persist(new SalesReturnLineJpaEntity(r.id(), l));
            }
            em.flush();
            return r;
        } catch (PersistenceException e) {
            if (PersistenceErrors.isUniqueViolation(e, "uq_sales_returns_tenant_request")) {
                throw new DuplicateClientRequestException();
            }
            throw e;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SalesReturn> findById(UUID tenantId, UUID id) {
        return em.createQuery("select r from SalesReturnJpaEntity r where r.tenantId = :t and r.id = :id",
                        SalesReturnJpaEntity.class).setParameter("t", tenantId).setParameter("id", id)
                .getResultList().stream().findFirst().map(e -> assemble(List.of(e)).get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SalesReturn> findByClientRequestId(UUID tenantId, UUID clientRequestId) {
        return em.createQuery("select r from SalesReturnJpaEntity r where r.tenantId = :t "
                                + "and r.clientRequestId = :c", SalesReturnJpaEntity.class)
                .setParameter("t", tenantId).setParameter("c", clientRequestId)
                .getResultList().stream().findFirst().map(e -> assemble(List.of(e)).get(0));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SalesReturn> findByOrderId(UUID tenantId, UUID orderId) {
        return assemble(em.createQuery("select r from SalesReturnJpaEntity r where r.tenantId = :t "
                        + "and r.orderId = :o order by r.createdAt, r.id", SalesReturnJpaEntity.class)
                .setParameter("t", tenantId).setParameter("o", orderId).getResultList());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, ReturnPlanner.Prior> priorByOrderLine(UUID tenantId, UUID orderId) {
        List<Object[]> rows = em.createQuery("select l.orderLineId, sum(l.quantity), sum(l.refundAmount) "
                        + "from SalesReturnLineJpaEntity l, SalesReturnJpaEntity r "
                        + "where l.tenantId = :t and r.tenantId = :t and l.returnId = r.id and r.orderId = :o "
                        + "group by l.orderLineId", Object[].class)
                .setParameter("t", tenantId).setParameter("o", orderId).getResultList();
        Map<UUID, ReturnPlanner.Prior> out = new HashMap<>();
        for (Object[] row : rows) {
            out.put((UUID) row[0], new ReturnPlanner.Prior(((Number) row[1]).intValue(),
                    ((Number) row[2]).longValue()));
        }
        return out;
    }

    private List<SalesReturn> assemble(List<SalesReturnJpaEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = entities.stream().map(SalesReturnJpaEntity::getId).toList();
        List<SalesReturnLineJpaEntity> lineEntities = em.createQuery(
                        "select l from SalesReturnLineJpaEntity l where l.returnId in :ids",
                        SalesReturnLineJpaEntity.class).setParameter("ids", ids).getResultList();
        // titleId của dòng phiếu lấy từ dòng đơn gốc (snapshot, bất biến)
        Map<UUID, UUID> titleByOrderLine = new HashMap<>();
        List<UUID> orderLineIds = lineEntities.stream().map(SalesReturnLineJpaEntity::getOrderLineId).distinct()
                .toList();
        if (!orderLineIds.isEmpty()) {
            em.createQuery("select l.id, l.titleId from SalesOrderLineJpaEntity l where l.id in :ids",
                            Object[].class).setParameter("ids", orderLineIds).getResultList()
                    .forEach(row -> titleByOrderLine.put((UUID) row[0], (UUID) row[1]));
        }
        Map<UUID, List<ReturnLine>> byReturn = new HashMap<>();
        for (SalesReturnLineJpaEntity l : lineEntities) {
            byReturn.computeIfAbsent(l.getReturnId(), k -> new ArrayList<>()).add(new ReturnLine(l.getId(),
                    l.getOrderLineId(), titleByOrderLine.get(l.getOrderLineId()), l.getQuantity(),
                    Disposition.valueOf(l.getDisposition()), Money.of(l.getRefundAmount())));
        }
        return entities.stream().map(e -> new SalesReturn(e.getId(), e.getTenantId(), e.getReturnNo(),
                e.getOrderId(), e.getReason(), e.isLate(), e.getLateReason(), e.getDaysOverdue(),
                e.getClientRequestId(), e.getCreatedBy(), e.getCreatedAt(),
                byReturn.getOrDefault(e.getId(), List.of()))).toList();
    }
}

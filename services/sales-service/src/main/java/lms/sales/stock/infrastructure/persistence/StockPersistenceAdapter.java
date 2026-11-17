package lms.sales.stock.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import lms.sales.shared.infrastructure.persistence.PersistenceErrors;
import lms.sales.stock.application.StockPort;
import lms.sales.stock.domain.MovementType;
import lms.sales.stock.domain.StockItem;
import lms.sales.stock.domain.StockMovement;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cài StockPort. {@link #tryApply} là MỘT câu JPQL bulk UPDATE có điều kiện (không native, có tenant_id tường minh
 * bên cạnh bộ lọc @TenantId và RLS); số dòng bị ảnh hưởng quyết định thành công. Sau bulk update xóa persistence
 * context để lần đọc kế tiếp thấy bộ đếm mới (entity đang giữ trong context sẽ cũ).
 */
@Repository
class StockPersistenceAdapter implements StockPort {

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional(readOnly = true)
    public Optional<StockItem> find(UUID tenantId, UUID titleId) {
        return queryByTitle(tenantId, titleId, false).map(StockPersistenceAdapter::toDomain);
    }

    @Override
    @Transactional
    public Optional<StockItem> findForUpdate(UUID tenantId, UUID titleId) {
        return queryByTitle(tenantId, titleId, true).map(StockPersistenceAdapter::toDomain);
    }

    private Optional<StockItemJpaEntity> queryByTitle(UUID tenantId, UUID titleId, boolean lock) {
        var q = em.createQuery("select s from StockItemJpaEntity s where s.tenantId = :t and s.titleId = :title",
                StockItemJpaEntity.class).setParameter("t", tenantId).setParameter("title", titleId);
        if (lock) {
            q.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        }
        return q.getResultList().stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, StockItem> findByTitleIds(UUID tenantId, Collection<UUID> titleIds) {
        Map<UUID, StockItem> out = new HashMap<>();
        if (titleIds == null || titleIds.isEmpty()) {
            return out;
        }
        em.createQuery("select s from StockItemJpaEntity s where s.tenantId = :t and s.titleId in :ids",
                        StockItemJpaEntity.class)
                .setParameter("t", tenantId).setParameter("ids", List.copyOf(titleIds)).getResultList()
                .forEach(e -> out.put(e.getTitleId(), toDomain(e)));
        return out;
    }

    @Override
    @Transactional
    public StockItem insert(StockItem item) {
        StockItemJpaEntity entity = new StockItemJpaEntity(item.id(), item.titleId(), (int) item.onHand(),
                (int) item.reserved(), item.sellingPrice() == null ? null : item.sellingPrice().amount(),
                item.lowStockThreshold(), item.active());
        try {
            em.persist(entity);
            em.flush();
        } catch (PersistenceException e) {
            if (PersistenceErrors.isUniqueViolation(e, "uq_stock_items_tenant_title")) {
                throw new SalesException(ErrorCode.STOCK_ITEM_EXISTS, "Đầu sách đã được đưa vào bán");
            }
            throw e;
        }
        return toDomain(entity);
    }

    @Override
    @Transactional
    public StockItem saveAttributes(StockItem item) {
        StockItemJpaEntity entity = em.find(StockItemJpaEntity.class, item.id());
        if (entity == null) {
            throw new SalesException(ErrorCode.STOCK_ITEM_NOT_FOUND, "Không có mặt hàng");
        }
        entity.updateAttributes(item.sellingPrice() == null ? null : item.sellingPrice().amount(),
                item.lowStockThreshold(), item.active());
        em.flush();
        return toDomain(entity);
    }

    @Override
    @Transactional
    public boolean tryApply(UUID tenantId, UUID titleId, long deltaOnHand, long deltaReserved) {
        int dOn;
        int dRes;
        try {
            dOn = Math.toIntExact(deltaOnHand);
            dRes = Math.toIntExact(deltaReserved);
        } catch (ArithmeticException e) {
            throw SalesException.invalidQuantity("Số lượng vượt giới hạn");
        }
        em.flush();
        int rows = em.createQuery("update StockItemJpaEntity s set s.onHand = s.onHand + :dOn, "
                        + "s.reserved = s.reserved + :dRes, s.updatedAt = :now "
                        + "where s.tenantId = :t and s.titleId = :title "
                        + "and s.onHand + :dOn >= 0 and s.reserved + :dRes >= 0 "
                        + "and s.reserved + :dRes <= s.onHand + :dOn")
                .setParameter("dOn", dOn).setParameter("dRes", dRes).setParameter("now", Instant.now())
                .setParameter("t", tenantId).setParameter("title", titleId).executeUpdate();
        em.clear();
        return rows == 1;
    }

    @Override
    @Transactional
    public void append(StockMovement m) {
        UUID itemId = em.createQuery("select s.id from StockItemJpaEntity s where s.tenantId = :t "
                        + "and s.titleId = :title", UUID.class)
                .setParameter("t", m.tenantId()).setParameter("title", m.titleId()).getResultList().stream()
                .findFirst().orElseThrow(() -> SalesException.notRegistered(m.titleId()));
        em.persist(new StockLedgerJpaEntity(m.id(), itemId, m.type().name(), (int) m.deltaOnHand(),
                (int) m.deltaReserved(), m.refType(), m.refId(), m.reason(), m.actorId(), m.occurredAt()));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<StockItem> listActive(UUID tenantId, int page, int size) {
        long total = em.createQuery("select count(s) from StockItemJpaEntity s where s.tenantId = :t "
                + "and s.active = true", Long.class).setParameter("t", tenantId).getSingleResult();
        List<StockItem> rows = em.createQuery("select s from StockItemJpaEntity s where s.tenantId = :t "
                        + "and s.active = true order by s.createdAt desc, s.id", StockItemJpaEntity.class)
                .setParameter("t", tenantId).setFirstResult(page * size).setMaxResults(size).getResultList()
                .stream().map(StockPersistenceAdapter::toDomain).toList();
        return new PageResult<>(rows, total, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<StockItem> list(UUID tenantId, boolean lowStockOnly, int tenantThreshold, int page, int size) {
        String where = "from StockItemJpaEntity s where s.tenantId = :t"
                + (lowStockOnly ? " and (s.onHand - s.reserved) <= coalesce(s.lowStockThreshold, :thr)" : "");
        var count = em.createQuery("select count(s) " + where, Long.class).setParameter("t", tenantId);
        var items = em.createQuery("select s " + where + " order by s.createdAt desc, s.id", StockItemJpaEntity.class)
                .setParameter("t", tenantId);
        if (lowStockOnly) {
            count.setParameter("thr", tenantThreshold);
            items.setParameter("thr", tenantThreshold);
        }
        List<StockItem> rows = items.setFirstResult(page * size).setMaxResults(size).getResultList().stream()
                .map(StockPersistenceAdapter::toDomain).toList();
        return new PageResult<>(rows, count.getSingleResult(), page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<StockMovement> ledger(UUID tenantId, UUID titleId, int page, int size) {
        Optional<UUID> itemId = itemId(tenantId, titleId);
        if (itemId.isEmpty()) {
            return new PageResult<>(List.of(), 0, page, size);
        }
        long total = em.createQuery("select count(l) from StockLedgerJpaEntity l where l.tenantId = :t "
                        + "and l.stockItemId = :item", Long.class)
                .setParameter("t", tenantId).setParameter("item", itemId.get()).getSingleResult();
        List<StockMovement> rows = em.createQuery("select l from StockLedgerJpaEntity l where l.tenantId = :t "
                                + "and l.stockItemId = :item order by l.occurredAt desc, l.id desc",
                        StockLedgerJpaEntity.class)
                .setParameter("t", tenantId).setParameter("item", itemId.get())
                .setFirstResult(page * size).setMaxResults(size).getResultList().stream()
                .map(l -> new StockMovement(l.getId(), l.getTenantId(), titleId,
                        MovementType.valueOf(l.getMovementType()), l.getDeltaOnHand(), l.getDeltaReserved(),
                        l.getRefType(), l.getRefId(), l.getReason(), l.getActorId(), l.getOccurredAt())).toList();
        return new PageResult<>(rows, total, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public LedgerTotals ledgerTotals(UUID tenantId, UUID titleId) {
        Optional<UUID> itemId = itemId(tenantId, titleId);
        if (itemId.isEmpty()) {
            return new LedgerTotals(0, 0);
        }
        Object[] row = em.createQuery("select coalesce(sum(l.deltaOnHand), 0), coalesce(sum(l.deltaReserved), 0) "
                        + "from StockLedgerJpaEntity l where l.tenantId = :t and l.stockItemId = :item", Object[].class)
                .setParameter("t", tenantId).setParameter("item", itemId.get()).getSingleResult();
        return new LedgerTotals(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
    }

    private Optional<UUID> itemId(UUID tenantId, UUID titleId) {
        return em.createQuery("select s.id from StockItemJpaEntity s where s.tenantId = :t and s.titleId = :title",
                UUID.class).setParameter("t", tenantId).setParameter("title", titleId).getResultList().stream()
                .findFirst();
    }

    private static StockItem toDomain(StockItemJpaEntity e) {
        return StockItem.restore(e.getId(), e.getTenantId(), e.getTitleId(), e.getOnHand(), e.getReserved(),
                e.getSellingPrice() == null ? null : Money.of(e.getSellingPrice()), e.getLowStockThreshold(),
                e.isActive(), 0);
    }
}

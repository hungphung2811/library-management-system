package lms.sales.stock.infrastructure.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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
import lms.sales.stock.application.StorefrontRow;
import lms.sales.stock.application.StorefrontSort;
import lms.sales.stock.domain.MovementType;
import lms.sales.stock.domain.StockItem;
import lms.sales.stock.domain.StockItemDetails;
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

    private static final int REPORT_TIMEOUT_MS = 20_000;
    private static final int IN_LIMIT = 1_000;

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
        applyDetails(entity, item.details());
        try {
            em.persist(entity);
            em.flush();
        } catch (PersistenceException e) {
            if (PersistenceErrors.isUniqueViolation(e, "uq_stock_items_tenant_sku")) {
                throw new SalesException(ErrorCode.SKU_EXISTS, "SKU đã được dùng cho mặt hàng khác");
            }
            if (PersistenceErrors.isUniqueViolation(e, "uq_stock_items_tenant_title")) {
                throw new SalesException(ErrorCode.STOCK_ITEM_EXISTS, "Đầu sách đã được đưa vào bán");
            }
            throw e;
        }
        return toDomain(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Set<UUID> titleIdsWithStockItem(UUID tenantId, Collection<UUID> titleIds) {
        java.util.Set<UUID> out = new java.util.HashSet<>();
        List<UUID> all = List.copyOf(new java.util.LinkedHashSet<>(titleIds));
        for (int from = 0; from < all.size(); from += IN_LIMIT) {
            out.addAll(em.createQuery("select s.titleId from StockItemJpaEntity s where s.tenantId = :t "
                            + "and s.titleId in :ids", UUID.class).setParameter("t", tenantId)
                    .setParameter("ids", all.subList(from, Math.min(all.size(), from + IN_LIMIT))).getResultList());
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Set<String> existingSkus(UUID tenantId, Collection<String> skus) {
        java.util.Set<String> out = new java.util.HashSet<>();
        List<String> all = List.copyOf(new java.util.LinkedHashSet<>(skus));
        for (int from = 0; from < all.size(); from += IN_LIMIT) {
            out.addAll(em.createQuery("select s.sku from StockItemJpaEntity s where s.tenantId = :t "
                            + "and s.sku in :skus", String.class).setParameter("t", tenantId)
                    .setParameter("skus", all.subList(from, Math.min(all.size(), from + IN_LIMIT))).getResultList());
        }
        return out;
    }

    @Override
    @Transactional
    public void insertAll(List<StockItem> items) {
        if (items.isEmpty()) {
            return;
        }
        try {
            for (StockItem item : items) {
                StockItemJpaEntity entity = new StockItemJpaEntity(item.id(), item.titleId(), (int) item.onHand(),
                        (int) item.reserved(), item.sellingPrice() == null ? null : item.sellingPrice().amount(),
                        item.lowStockThreshold(), item.active());
                applyDetails(entity, item.details());
                em.persist(entity);
            }
            em.flush();
        } catch (PersistenceException e) {
            if (PersistenceErrors.isUniqueViolation(e, "uq_stock_items_tenant_sku")) {
                throw new SalesException(ErrorCode.SKU_EXISTS, "SKU đã được dùng cho mặt hàng khác");
            }
            if (PersistenceErrors.isUniqueViolation(e, "uq_stock_items_tenant_title")) {
                throw new SalesException(ErrorCode.STOCK_ITEM_EXISTS, "Đầu sách đã được đưa vào bán");
            }
            throw e;
        }
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
        applyDetails(entity, item.details());
        try {
            em.flush();
        } catch (PersistenceException e) {
            if (PersistenceErrors.isUniqueViolation(e, "uq_stock_items_tenant_sku")) {
                throw new SalesException(ErrorCode.SKU_EXISTS, "SKU đã được dùng cho mặt hàng khác");
            }
            throw e;
        }
        return toDomain(entity);
    }

    private static void applyDetails(StockItemJpaEntity entity, StockItemDetails d) {
        entity.updateDetails(d.sku(), d.supplier(), d.costPrice() == null ? null : d.costPrice().amount(),
                d.binLocation(), d.reorderQuantity(), d.lastRestockedAt(), d.note());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StockItem> findBySku(UUID tenantId, String sku) {
        if (sku == null) {
            return Optional.empty();
        }
        return em.createQuery("select s from StockItemJpaEntity s where s.tenantId = :t and s.sku = :sku",
                StockItemJpaEntity.class).setParameter("t", tenantId).setParameter("sku", sku).getResultList()
                .stream().findFirst().map(StockPersistenceAdapter::toDomain);
    }

    @Override
    @Transactional
    public void touchLastRestocked(UUID tenantId, Collection<UUID> titleIds, Instant at) {
        if (titleIds == null || titleIds.isEmpty()) {
            return;
        }
        em.createQuery("update StockItemJpaEntity s set s.lastRestockedAt = :at "
                        + "where s.tenantId = :t and s.titleId in :ids "
                        + "and (s.lastRestockedAt is null or s.lastRestockedAt < :at)")
                .setParameter("at", at).setParameter("t", tenantId).setParameter("ids", List.copyOf(titleIds))
                .executeUpdate();
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
    public PageResult<StorefrontRow> listStorefront(UUID tenantId, StorefrontSort sort, boolean inStockOnly,
                                                    int page, int size) {
        // Mọi mảnh JPQL là hằng chữ; order by chọn bằng switch trên enum, không nối giá trị từ request.
        String where = "from StockItemJpaEntity s where s.tenantId = :t and s.active = true"
                + (sort.byPrice() ? " and s.effectivePrice is not null" : "")
                + (inStockOnly ? " and (s.onHand - s.reserved) > 0" : "");
        String order = switch (sort) {
            case NEWEST -> " order by s.createdAt desc, s.id";
            case PRICE_ASC -> " order by s.effectivePrice asc, s.id asc";
            case PRICE_DESC -> " order by s.effectivePrice desc, s.id desc";
        };
        long total = em.createQuery("select count(s) " + where, Long.class).setParameter("t", tenantId)
                .getSingleResult();
        List<StorefrontRow> rows = em.createQuery("select s " + where + order, StockItemJpaEntity.class)
                .setParameter("t", tenantId).setFirstResult(page * size).setMaxResults(size).getResultList()
                .stream().map(StockPersistenceAdapter::toRow).toList();
        return new PageResult<>(rows, total, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, StorefrontRow> findStorefrontRows(UUID tenantId, Collection<UUID> titleIds) {
        Map<UUID, StorefrontRow> out = new HashMap<>();
        if (titleIds == null || titleIds.isEmpty()) {
            return out;
        }
        em.createQuery("select s from StockItemJpaEntity s where s.tenantId = :t and s.active = true "
                        + "and s.titleId in :ids", StockItemJpaEntity.class)
                .setParameter("t", tenantId).setParameter("ids", List.copyOf(titleIds)).getResultList()
                .forEach(e -> out.put(e.getTitleId(), toRow(e)));
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> titleIdsMissingCoverPrice(UUID tenantId, int limit) {
        return em.createQuery("select s.titleId from StockItemJpaEntity s where s.tenantId = :t and s.active = true "
                        + "and s.sellingPrice is null and s.coverPriceSnapshot is null order by s.id", UUID.class)
                .setParameter("t", tenantId).setMaxResults(limit).getResultList();
    }

    @Override
    @Transactional
    public void refreshCoverPrices(UUID tenantId, Map<UUID, Long> pricesByTitle) {
        if (pricesByTitle == null || pricesByTitle.isEmpty()) {
            return;
        }
        List<Map.Entry<UUID, Long>> valid = pricesByTitle.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getValue() != null && e.getValue() >= 0).toList();
        // Một câu UPDATE ... CASE cho mỗi lô COVER_PRICE_CHUNK mặt hàng (thay vì một UPDATE cho từng mặt hàng).
        // Mảnh JPQL chỉ là hằng chữ và tên tham số do ta sinh (id0, v0...), giá trị luôn bind qua tham số.
        for (int from = 0; from < valid.size(); from += COVER_PRICE_CHUNK) {
            List<Map.Entry<UUID, Long>> chunk = valid.subList(from, Math.min(from + COVER_PRICE_CHUNK, valid.size()));
            StringBuilder jpql = new StringBuilder("update StockItemJpaEntity s set s.coverPriceSnapshot = case");
            for (int i = 0; i < chunk.size(); i++) {
                jpql.append(" when s.titleId = :id").append(i).append(" then :v").append(i);
            }
            jpql.append(" else s.coverPriceSnapshot end where s.tenantId = :t and s.titleId in :ids");
            var q = em.createQuery(jpql.toString()).setParameter("t", tenantId)
                    .setParameter("ids", chunk.stream().map(Map.Entry::getKey).toList());
            for (int i = 0; i < chunk.size(); i++) {
                q.setParameter("id" + i, chunk.get(i).getKey()).setParameter("v" + i, chunk.get(i).getValue());
            }
            q.executeUpdate();
        }
    }

    /** Số mặt hàng tối đa trong một câu UPDATE đồng bộ giá bìa (2 tham số mỗi mặt hàng, nằm xa giới hạn driver). */
    private static final int COVER_PRICE_CHUNK = 100;

    private static StorefrontRow toRow(StockItemJpaEntity e) {
        return new StorefrontRow(e.getTitleId(), e.getSellingPrice() == null ? null : Money.of(e.getSellingPrice()),
                e.getCoverPriceSnapshot(), e.getOnHand() - e.getReserved() > 0, e.getCreatedAt(), e.getWorkId());
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<WorkGroup> listStorefrontByWork(UUID tenantId, StorefrontSort sort, boolean inStockOnly,
                                                      int page, int size) {
        // Mảnh JPQL là hằng chữ; order by chọn bằng switch trên enum. Khóa nhóm = COALESCE(work_id, title_id).
        String base = "from StockItemJpaEntity s where s.tenantId = :t and s.active = true"
                + (sort.byPrice() ? " and s.effectivePrice is not null" : "");
        String key = "coalesce(s.workId, s.titleId)";
        String having = inStockOnly ? " having max(case when s.onHand - s.reserved > 0 then 1 else 0 end) = 1" : "";
        String order = switch (sort) {
            case NEWEST -> " order by max(s.createdAt) desc, " + key;
            case PRICE_ASC -> " order by min(s.effectivePrice) asc, " + key + " asc";
            case PRICE_DESC -> " order by min(s.effectivePrice) desc, " + key + " desc";
        };
        // Nhóm có ít nhất một ấn bản còn hàng <=> có dòng còn hàng, nên đếm bằng điều kiện trên dòng thay cho HAVING.
        long total = em.createQuery("select count(distinct " + key + ") " + base
                        + (inStockOnly ? " and (s.onHand - s.reserved) > 0" : ""), Long.class)
                .setParameter("t", tenantId).getSingleResult();
        List<Object[]> groups = em.createQuery("select " + key + ", count(s), min(s.effectivePrice), "
                        + "max(s.createdAt), max(case when s.onHand - s.reserved > 0 then 1 else 0 end) "
                        + base + " group by " + key + having + order, Object[].class)
                .setParameter("t", tenantId).setFirstResult(page * size).setMaxResults(size).getResultList();
        if (groups.isEmpty()) {
            return new PageResult<>(List.of(), total, page, size);
        }
        List<UUID> keys = groups.stream().map(g -> (UUID) g[0]).toList();
        Comparator<StockItemJpaEntity> pick = Comparator
                .comparing((StockItemJpaEntity e) -> e.getOnHand() - e.getReserved() > 0 ? 0 : 1)
                .thenComparing(StockItemJpaEntity::getEffectivePrice, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(StockItemJpaEntity::getCreatedAt, Comparator.reverseOrder())
                .thenComparing(StockItemJpaEntity::getId);
        Map<UUID, StockItemJpaEntity> best = new HashMap<>();
        for (StockItemJpaEntity e : em.createQuery("select s from StockItemJpaEntity s where s.tenantId = :t "
                        + "and s.active = true and (s.workId in :keys or (s.workId is null and s.titleId in :keys))",
                StockItemJpaEntity.class).setParameter("t", tenantId).setParameter("keys", keys).getResultList()) {
            UUID k = e.getWorkId() != null ? e.getWorkId() : e.getTitleId();
            best.merge(k, e, (a, b) -> pick.compare(a, b) <= 0 ? a : b);
        }
        List<WorkGroup> out = new ArrayList<>();
        for (Object[] g : groups) {
            StockItemJpaEntity rep = best.get((UUID) g[0]);
            if (rep == null) {
                continue; // nhóm vừa đổi giữa hai câu truy vấn: bỏ khỏi trang, lần sau tự đúng
            }
            out.add(new WorkGroup(toRow(rep), ((Number) g[1]).intValue(),
                    g[2] == null ? null : ((Number) g[2]).longValue(), ((Number) g[4]).intValue() == 1,
                    (Instant) g[3]));
        }
        return new PageResult<>(out, total, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StorefrontRow> findStorefrontRowsByWork(UUID tenantId, UUID workId) {
        return em.createQuery("select s from StockItemJpaEntity s where s.tenantId = :t and s.active = true "
                        + "and s.workId = :w order by s.createdAt, s.id", StockItemJpaEntity.class)
                .setParameter("t", tenantId).setParameter("w", workId).getResultList().stream()
                .map(StockPersistenceAdapter::toRow).toList();
    }

    @Override
    @Transactional
    public void refreshWorkIds(UUID tenantId, Map<UUID, UUID> workIdsByTitle) {
        if (workIdsByTitle == null || workIdsByTitle.isEmpty()) {
            return;
        }
        List<Map.Entry<UUID, UUID>> valid = workIdsByTitle.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getValue() != null).toList();
        for (int from = 0; from < valid.size(); from += COVER_PRICE_CHUNK) {
            List<Map.Entry<UUID, UUID>> chunk = valid.subList(from, Math.min(from + COVER_PRICE_CHUNK, valid.size()));
            StringBuilder jpql = new StringBuilder("update StockItemJpaEntity s set s.workId = case");
            for (int i = 0; i < chunk.size(); i++) {
                jpql.append(" when s.titleId = :id").append(i).append(" then :v").append(i);
            }
            jpql.append(" else s.workId end where s.tenantId = :t and s.titleId in :ids");
            var q = em.createQuery(jpql.toString()).setParameter("t", tenantId)
                    .setParameter("ids", chunk.stream().map(Map.Entry::getKey).toList());
            for (int i = 0; i < chunk.size(); i++) {
                q.setParameter("id" + i, chunk.get(i).getKey()).setParameter("v" + i, chunk.get(i).getValue());
            }
            q.executeUpdate();
        }
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
    public PageResult<StockItem> listForReport(UUID tenantId, boolean lowStockOnly, int tenantThreshold, int page,
                                               int size) {
        String where = "from StockItemJpaEntity s where s.tenantId = :t"
                + (lowStockOnly ? " and (s.onHand - s.reserved) <= coalesce(s.lowStockThreshold, :thr)" : "");
        var count = em.createQuery("select count(s) " + where, Long.class).setParameter("t", tenantId)
                .setHint("jakarta.persistence.query.timeout", REPORT_TIMEOUT_MS);
        var items = em.createQuery("select s " + where + " order by (s.onHand - s.reserved), s.titleId",
                StockItemJpaEntity.class).setParameter("t", tenantId)
                .setHint("jakarta.persistence.query.timeout", REPORT_TIMEOUT_MS);
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
                e.isActive(), 0,
                new StockItemDetails(e.getSku(), e.getSupplier(),
                        e.getCostPrice() == null ? null : Money.of(e.getCostPrice()), e.getBinLocation(),
                        e.getReorderQuantity(), e.getLastRestockedAt(), e.getNote()));
    }
}

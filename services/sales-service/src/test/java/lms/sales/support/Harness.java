package lms.sales.support;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.sales.ordering.application.CommitPosOrderUseCase;
import lms.sales.ordering.application.OrderQueryUseCases;
import lms.sales.ordering.application.PlacePosOrderUseCase;
import lms.sales.ordering.application.CommitOnlineOrderUseCase;
import lms.sales.ordering.application.CommitReturnUseCase;
import lms.sales.ordering.application.CreateReturnUseCase;
import lms.sales.ordering.application.OrderWorkflowUseCases;
import lms.sales.ordering.application.PlaceOnlineOrderUseCase;
import lms.sales.ordering.application.RefundRepository;
import lms.sales.ordering.application.RefundUseCases;
import lms.sales.ordering.application.ReturnQueryUseCases;
import lms.sales.ordering.application.SalesOrderRepository;
import lms.sales.ordering.application.SalesReturnRepository;
import lms.sales.ordering.application.ShopOrderUseCases;
import lms.sales.ordering.domain.Refund;
import lms.sales.ordering.domain.RefundStatus;
import lms.sales.ordering.domain.ReturnLine;
import lms.sales.ordering.domain.ReturnPlanner;
import lms.sales.ordering.domain.SalesReturn;
import lms.sales.shared.application.SalesAuditQueryPort;
import lms.sales.shared.application.SalesAuditQueryUseCase;
import lms.sales.shared.application.TenantDirectoryPort;
import lms.sales.stock.application.StorefrontQueryUseCases;
import lms.sales.stock.application.StorefrontRow;
import lms.sales.stock.application.StorefrontSort;
import lms.sales.ordering.domain.SalesOrder;
import lms.sales.ordering.application.ExpireOnlineReservationsUseCase;
import lms.sales.ordering.application.OnlineReservationExpiry;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.settings.application.OrderAbusePolicyPort;
import lms.sales.settings.application.OrderAbusePolicyUseCases;
import lms.sales.settings.domain.OrderAbusePolicy;
import lms.sales.settings.application.SalesSettingsPort;
import lms.sales.settings.application.UpdateSalesSettingsUseCase;
import lms.sales.settings.domain.SalesSettings;
import lms.sales.shared.application.DocumentNumberPort;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.DuplicateClientRequestException;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.AdjustStockUseCase;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.stock.application.GoodsReceiptCreator;
import lms.sales.stock.application.GoodsReceiptRepository;
import lms.sales.stock.application.GetStockItemUseCase;
import lms.sales.stock.application.GoodsReceiptUseCases;
import lms.sales.stock.application.ReconcileStockUseCase;
import lms.sales.stock.application.RegisterStockItemUseCase;
import lms.sales.stock.application.StockPort;
import lms.sales.stock.application.StockService;
import lms.sales.stock.application.StocktakeUseCase;
import lms.sales.stock.application.SupplierRepository;
import lms.sales.stock.application.SupplierUseCases;
import lms.sales.stock.domain.GoodsReceipt;
import lms.sales.stock.domain.ReceiptStatus;
import lms.sales.stock.domain.StockItem;
import lms.sales.stock.domain.StockMovement;
import lms.sales.stock.domain.Supplier;

/**
 * Bộ giả trong bộ nhớ cho test không Spring: cài các port theo đúng hợp đồng (tryApply có điều kiện như một câu
 * UPDATE), {@link #tx} mô phỏng rollback bằng snapshot trạng thái.
 */
public final class Harness {

    public final UUID tenant = UUID.randomUUID();
    public final UUID user = UUID.randomUUID();
    public final MutableClock clock = new MutableClock(Instant.parse("2026-11-17T03:00:00Z"));

    // trạng thái
    private Map<UUID, StockItem> items = new LinkedHashMap<>();
    private List<StockMovement> ledger = new ArrayList<>();
    private Map<UUID, SalesOrder> orders = new LinkedHashMap<>();
    private Map<UUID, SalesReturn> returns = new LinkedHashMap<>();
    private Map<UUID, Refund> refunds = new LinkedHashMap<>();
    public final java.util.Set<UUID> knownTenants = new java.util.LinkedHashSet<>();
    public final List<Integer> lockedOrders = new ArrayList<>();
    private Map<UUID, Supplier> suppliers = new LinkedHashMap<>();
    private Map<UUID, GoodsReceipt> receipts = new LinkedHashMap<>();
    private SalesSettings settings;
    private final List<SalesAuditPort.AuditEntry> audits = new ArrayList<>();
    private final Map<String, Integer> sequences = new HashMap<>();
    public final Map<UUID, CatalogLookupPort.CatalogTitle> catalogTitles = new HashMap<>();
    public SalesOrder raceWinner; // nếu đặt: lần save đơn kế tiếp thua cuộc đua clientRequestId
    public int catalogCalls;
    // ADR-0019: bản sao giá bìa + ngày đưa vào bán của mặt hàng (StockItem domain không mang hai giá trị này)
    public final Map<UUID, Long> coverSnapshots = new HashMap<>();
    public final Map<UUID, Instant> listedAt = new HashMap<>();
    /** ADR-0026: snapshot stock_items.work_id (giả lập). */
    public final Map<UUID, UUID> workSnapshots = new HashMap<>();
    public final List<Map<UUID, UUID>> workWrites = new ArrayList<>();
    public final List<Map<UUID, Long>> snapshotWrites = new ArrayList<>();
    public boolean failSnapshotWrites;
    public int storefrontListCalls;

    public final StockPort stockPort = new StockAdapter();
    public final SalesOrderRepository orderRepo = new OrderAdapter();
    public final SalesReturnRepository returnRepo = new ReturnAdapter();
    public final RefundRepository refundRepo = new RefundAdapter();
    public final TenantDirectoryPort tenantDirectory = new TenantDirectoryPort() {
        public void touch(UUID tenantId) {
            knownTenants.add(tenantId);
        }

        public List<UUID> listAll() {
            return List.copyOf(knownTenants);
        }
    };
    public final SupplierRepository supplierRepo = new SupplierAdapter();
    public final GoodsReceiptRepository receiptRepo = new ReceiptAdapter();
    public final SalesSettingsPort settingsPort = new SettingsAdapter();
    public final SalesAuditPort auditPort = audits::add;
    public final DocumentNumberPort numbers = (t, seq) -> seq.charAt(0) + "-" + sequences.merge(seq, 1, Integer::sum);
    public final Map<UUID, CatalogLookupPort.CatalogPreview> catalogPreviews = new HashMap<>();
    public final CatalogLookupPort catalog = new CatalogLookupPort() {
        public Map<UUID, CatalogTitle> lookup(Collection<UUID> ids) {
            catalogCalls++;
            Map<UUID, CatalogTitle> out = new HashMap<>();
            for (UUID id : ids) {
                if (catalogTitles.containsKey(id)) {
                    out.put(id, catalogTitles.get(id));
                }
            }
            return out;
        }

        public List<CatalogTitle> lookupByKeyword(String keyword) {
            catalogCalls++;
            return catalogTitles.values().stream()
                    .filter(t -> t.name().toLowerCase().contains(keyword.toLowerCase())).toList();
        }

        public Optional<CatalogPreview> preview(UUID titleId) {
            catalogCalls++;
            return Optional.ofNullable(catalogPreviews.get(titleId));
        }
    };

    public final StockService stockService = new StockService(stockPort, clock);
    public final GetSalesSettingsUseCase getSettings = new GetSalesSettingsUseCase(settingsPort);
    public final UpdateSalesSettingsUseCase updateSettings = new UpdateSalesSettingsUseCase(settingsPort, auditPort,
            clock);
    public final RegisterStockItemUseCase registerItem = new RegisterStockItemUseCase(stockPort, catalog, getSettings,
            auditPort, clock);
    public final GetStockItemUseCase getItem = new GetStockItemUseCase(stockPort, catalog, getSettings);
    public final lms.sales.stock.application.StockItemDetailsUseCases itemDetails =
            new lms.sales.stock.application.StockItemDetailsUseCases(stockPort, auditPort, clock);
    public final AdjustStockUseCase adjust = new AdjustStockUseCase(stockService, auditPort, clock);
    public final StocktakeUseCase stocktake = new StocktakeUseCase(stockPort, stockService, auditPort, clock);
    public final ReconcileStockUseCase reconcile = new ReconcileStockUseCase(stockPort);
    public final SupplierUseCases supplierUseCases = new SupplierUseCases(supplierRepo, auditPort, clock);
    public final GoodsReceiptUseCases receiptUseCases = new GoodsReceiptUseCases(new GoodsReceiptCreator(receiptRepo,
            supplierRepo, stockPort, numbers, auditPort, clock), receiptRepo, stockService, auditPort, clock);
    // ADR-0021: POS hai bước. Bản dùng thật (createWithPayment) giữ nguyên; create/createWithDiscount ở đây là lối tắt
    // cho test cũ: tạo đơn hai bước rồi xác nhận thu tay ngay, nên kết quả vẫn là đơn COMPLETED + PAID.
    public final InMemoryIntents intents = new InMemoryIntents();
    public final lms.sales.ordering.application.PaymentIntentUseCases intentUseCases =
            new lms.sales.ordering.application.PaymentIntentUseCases(intents, orderRepo, stockService, auditPort,
                    clock);
    public final lms.sales.ordering.application.CommitPosPendingOrderUseCase commitPending =
            new lms.sales.ordering.application.CommitPosPendingOrderUseCase(orderRepo, intents, java.util.List.of(), stockService,
                    getSettings, numbers, auditPort, tenantDirectory, returnRepo, clock,
                    java.time.Duration.ofMinutes(10), java.time.Duration.ofMinutes(10));
    public final PlacePosOrderUseCase placePos = new PlacePosOrderUseCase(orderRepo, catalog,
            new CommitPosOrderUseCase(orderRepo, stockService, getSettings, numbers, auditPort, clock, tenantDirectory,
                    returnRepo), commitPending) {
        @Override
        public lms.sales.ordering.application.OrderResponse create(
                lms.sales.ordering.application.PlacePosOrderCommand cmd) {
            return paidNow(cmd, createWithPayment(cmd));
        }

        @Override
        public lms.sales.ordering.application.OrderResponse createWithDiscount(
                lms.sales.ordering.application.PlacePosOrderCommand cmd) {
            return paidNow(cmd, createWithDiscountAndPayment(cmd));
        }

        private lms.sales.ordering.application.OrderResponse paidNow(
                lms.sales.ordering.application.PlacePosOrderCommand cmd,
                lms.sales.ordering.application.PosOrderResult r) {
            if (r.paymentIntent() == null) {
                return r.order();
            }
            boolean needsRef = cmd.paymentMethod() != lms.sales.ordering.domain.PaymentMethod.CASH;
            intentUseCases.confirm(cmd.tenantId(), cmd.userId(), r.paymentIntent().id(),
                    new lms.sales.ordering.application.PaymentIntentUseCases.ConfirmCommand(null,
                            needsRef ? "TEST-REF" : null));
            return lms.sales.ordering.application.OrderResponse.from(
                    orderRepo.findById(cmd.tenantId(), r.order().id()).orElseThrow());
        }
    };
    public final OrderQueryUseCases orderQueries = new OrderQueryUseCases(orderRepo);
    // Chống lạm dụng đặt hàng: chính sách mặc định (15 phút, 5 đơn chờ, 20 đơn/ngày, 50/dòng, 100/đơn), lưu trong bộ nhớ
    public OrderAbusePolicy policyStored;
    public final OrderAbusePolicyPort abusePolicyPort = new OrderAbusePolicyPort() {
        public Optional<OrderAbusePolicy> find(UUID t) {
            return Optional.ofNullable(policyStored);
        }

        public OrderAbusePolicy save(OrderAbusePolicy p) {
            policyStored = OrderAbusePolicy.restore(p.tenantId(), p.values(), p.version() + 1, p.updatedAt(),
                    p.updatedBy());
            return policyStored;
        }
    };
    public final OrderAbusePolicyUseCases abusePolicies = new OrderAbusePolicyUseCases(abusePolicyPort,
            new OrderAbusePolicy.Values(15, 5, 20, 50, 100, 5, 300), auditPort, clock);
    /** Giới hạn tốc độ giả: đặt {@code rateLimitedBuckets} để từ chối bucket tương ứng, ghi lại các lần gọi. */
    public final java.util.Set<String> rateLimitedBuckets = new java.util.HashSet<>();
    public final List<String> rateLimitCalls = new ArrayList<>();
    public final lms.cache.RateLimiter rateLimiter = new lms.cache.RateLimiter() {
        public boolean tryAcquire(String bucket, String subject, int limit, java.time.Duration window) {
            rateLimitCalls.add(bucket + "|" + subject + "|" + limit);
            return !rateLimitedBuckets.contains(bucket);
        }

        public boolean isEnabled() {
            return true;
        }
    };

    /** Đặt chính sách chống lạm dụng của tenant test (tăng version như lúc lưu thật). */
    public void setAbusePolicy(int minutes, int pending, int perDay, int perLine, int total) {
        long version = policyStored == null ? 0 : policyStored.version();
        abusePolicies.update(tenant, user, new OrderAbusePolicy.Values(minutes, pending, perDay, perLine, total, 5,
                300), version);
    }

    public final PlaceOnlineOrderUseCase placeOnline = new PlaceOnlineOrderUseCase(orderRepo, catalog,
            new CommitOnlineOrderUseCase(orderRepo, stockService, getSettings, numbers, auditPort, tenantDirectory,
                    clock, abusePolicies, (t, c) -> { },
                    new lms.sales.ordering.application.AntiAbuseService(
                            new lms.sales.ordering.application.AntiAbuseService.Thresholds(5, 30, 50, 80))),
            abusePolicies);
    public final OnlineReservationExpiry reservationExpiry;
    public final ExpireOnlineReservationsUseCase expireReservations;
    public final OrderWorkflowUseCases workflow = new OrderWorkflowUseCases(orderRepo, refundRepo, stockService,
            getSettings, auditPort, clock);
    public final ShopOrderUseCases shopOrders = new ShopOrderUseCases(orderRepo, workflow);
    {
        reservationExpiry = new OnlineReservationExpiry(orderRepo, workflow, clock);
        expireReservations = new ExpireOnlineReservationsUseCase(orderRepo, reservationExpiry, clock, 100);
    }
    public final CommitReturnUseCase commitReturn = new CommitReturnUseCase(orderRepo, returnRepo, refundRepo,
            stockService, getSettings, numbers, auditPort, clock);
    public final CreateReturnUseCase createReturn = new CreateReturnUseCase(commitReturn, returnRepo);
    public final ReturnQueryUseCases returnQueries = new ReturnQueryUseCases(returnRepo, refundRepo, orderRepo);
    public final RefundUseCases refundUseCases = new RefundUseCases(refundRepo, orderRepo, auditPort, clock);
    public final StorefrontQueryUseCases storefront = new StorefrontQueryUseCases(stockPort, catalog);
    public final SalesAuditQueryUseCase auditQueries = new SalesAuditQueryUseCase(
            (tenant, c, page, size) -> {
                var all = audits.stream()
                        .filter(a -> c.action() == null || a.action().equals(c.action()))
                        .filter(a -> c.aggregateId() == null || c.aggregateId().equals(a.aggregateId()))
                        .map(a -> new SalesAuditQueryPort.Entry(UUID.randomUUID(), a.action(), a.aggregateType(),
                                a.aggregateId(), a.actorId(), a.occurredAt(), a.data())).toList();
                return new PageResult<>(all, all.size(), page, size);
            });

    /** Thêm đầu sách vào catalog giả và đưa vào bán với tồn ban đầu (qua phiếu điều chỉnh để sổ cái khớp). */
    public UUID book(String name, long coverPrice, Long sellingPrice, long initialStock) {
        UUID id = UUID.randomUUID();
        catalogTitles.put(id, new CatalogLookupPort.CatalogTitle(id, name, "Tác giả", "ISBN-" + name,
                BigDecimal.valueOf(coverPrice)));
        registerItem.execute(tenant, user, id, sellingPrice, null);
        if (initialStock > 0) {
            adjust.execute(tenant, user, id, initialStock, "tồn đầu kỳ");
        }
        return id;
    }

    public StockItem item(UUID titleId) {
        return items.get(titleId);
    }

    public List<StockMovement> ledger() {
        return List.copyOf(ledger);
    }

    public List<SalesAuditPort.AuditEntry> audits() {
        return List.copyOf(audits);
    }

    public int orderCount() {
        return orders.size();
    }

    /** Mô phỏng transaction: lỗi thì khôi phục trạng thái trước đó. */
    public <T> T tx(java.util.function.Supplier<T> body) {
        var snapItems = new LinkedHashMap<>(items);
        var snapLedger = new ArrayList<>(ledger);
        var snapOrders = new LinkedHashMap<>(orders);
        var snapReturns = new LinkedHashMap<>(returns);
        var snapRefunds = new LinkedHashMap<>(refunds);
        var snapSuppliers = new LinkedHashMap<>(suppliers);
        var snapReceipts = new LinkedHashMap<>(receipts);
        int snapAudits = audits.size();
        try {
            return body.get();
        } catch (RuntimeException e) {
            items = snapItems;
            ledger = snapLedger;
            orders = snapOrders;
            returns = snapReturns;
            refunds = snapRefunds;
            suppliers = snapSuppliers;
            receipts = snapReceipts;
            while (audits.size() > snapAudits) {
                audits.remove(audits.size() - 1);
            }
            throw e;
        }
    }

    public void txRun(Runnable body) {
        tx(() -> {
            body.run();
            return null;
        });
    }

    // ---- adapters ----

    private final class StockAdapter implements StockPort {
        public Optional<StockItem> find(UUID t, UUID titleId) {
            return Optional.ofNullable(items.get(titleId));
        }

        public Optional<StockItem> findForUpdate(UUID t, UUID titleId) {
            return find(t, titleId);
        }

        public Map<UUID, StockItem> findByTitleIds(UUID t, Collection<UUID> ids) {
            Map<UUID, StockItem> out = new HashMap<>();
            ids.forEach(id -> {
                if (items.containsKey(id)) {
                    out.put(id, items.get(id));
                }
            });
            return out;
        }

        public StockItem insert(StockItem item) {
            if (items.containsKey(item.titleId())) {
                throw new SalesException(ErrorCode.STOCK_ITEM_EXISTS, "exists");
            }
            items.put(item.titleId(), item);
            listedAt.put(item.titleId(), Instant.EPOCH.plusSeconds(items.size()));
            return item;
        }

        public StockItem saveAttributes(StockItem item) {
            StockItem cur = items.get(item.titleId());
            StockItem merged = StockItem.restore(cur.id(), cur.tenantId(), cur.titleId(), cur.onHand(),
                    cur.reserved(), item.sellingPrice(), item.lowStockThreshold(), item.active(), cur.version(),
                    item.details());
            items.put(item.titleId(), merged);
            return merged;
        }

        public Optional<StockItem> findBySku(UUID t, String sku) {
            return items.values().stream().filter(i -> sku != null && sku.equals(i.details().sku())).findFirst();
        }

        public void touchLastRestocked(UUID t, Collection<UUID> titleIds, Instant at) {
            for (UUID id : titleIds) {
                StockItem cur = items.get(id);
                if (cur != null && (cur.details().lastRestockedAt() == null
                        || cur.details().lastRestockedAt().isBefore(at))) {
                    items.put(id, cur.withDetails(cur.details().withLastRestockedAt(at)));
                }
            }
        }

        public boolean tryApply(UUID t, UUID titleId, long dOn, long dRes) {
            StockItem cur = items.get(titleId);
            if (cur == null) {
                return false;
            }
            long on = cur.onHand() + dOn;
            long res = cur.reserved() + dRes;
            if (on < 0 || res < 0 || res > on) { // cùng điều kiện với câu UPDATE
                return false;
            }
            items.put(titleId, cur.apply(dOn, dRes));
            return true;
        }

        public void append(StockMovement m) {
            ledger.add(m);
        }

        public PageResult<StockItem> list(UUID t, boolean lowOnly, int threshold, int page, int size) {
            var all = items.values().stream().filter(i -> !lowOnly || i.isLowStock(threshold)).toList();
            return new PageResult<>(all, all.size(), page, size);
        }

        private StorefrontRow row(StockItem i) {
            return new StorefrontRow(i.titleId(), i.sellingPrice(), coverSnapshots.get(i.titleId()),
                    i.available() > 0, listedAt.getOrDefault(i.titleId(), Instant.EPOCH), workSnapshots.get(i.titleId()));
        }

        private Long effective(StorefrontRow r) {
            return r.sellingPrice() != null ? Long.valueOf(r.sellingPrice().amount()) : r.coverPriceSnapshot();
        }

        public PageResult<StorefrontRow> listStorefront(UUID t, StorefrontSort sort, boolean inStockOnly, int page,
                                                        int size) {
            storefrontListCalls++;
            var rows = new ArrayList<>(items.values().stream().filter(StockItem::active).map(this::row)
                    .filter(r -> !inStockOnly || r.inStock())
                    .filter(r -> !sort.byPrice() || effective(r) != null).toList());
            java.util.Comparator<StorefrontRow> byId = java.util.Comparator.comparing(StorefrontRow::titleId);
            switch (sort) {
                case NEWEST -> rows.sort(java.util.Comparator.comparing(StorefrontRow::listedAt).reversed()
                        .thenComparing(byId));
                case PRICE_ASC -> rows.sort(java.util.Comparator.<StorefrontRow, Long>comparing(this::effective).thenComparing(byId));
                case PRICE_DESC -> rows.sort(java.util.Comparator.<StorefrontRow, Long>comparing(this::effective).thenComparing(byId)
                        .reversed());
            }
            int from = Math.min(rows.size(), page * size);
            return new PageResult<>(rows.subList(from, Math.min(rows.size(), from + size)), rows.size(), page, size);
        }

        public Map<UUID, StorefrontRow> findStorefrontRows(UUID t, Collection<UUID> ids) {
            Map<UUID, StorefrontRow> out = new HashMap<>();
            ids.forEach(id -> {
                StockItem i = items.get(id);
                if (i != null && i.active()) {
                    out.put(id, row(i));
                }
            });
            return out;
        }

        public PageResult<WorkGroup> listStorefrontByWork(UUID t, StorefrontSort sort, boolean inStockOnly, int page,
                                                          int size) {
            storefrontListCalls++;
            Map<UUID, List<StorefrontRow>> groups = new java.util.LinkedHashMap<>();
            items.values().stream().filter(StockItem::active).map(this::row)
                    .filter(r -> !sort.byPrice() || effective(r) != null)
                    .forEach(r -> groups.computeIfAbsent(r.workId() != null ? r.workId() : r.titleId(),
                            k -> new ArrayList<>()).add(r));
            java.util.Comparator<StorefrontRow> pick = java.util.Comparator
                    .comparing((StorefrontRow r) -> r.inStock() ? 0 : 1)
                    .thenComparing(this::effective, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                    .thenComparing(StorefrontRow::listedAt, java.util.Comparator.reverseOrder())
                    .thenComparing(StorefrontRow::titleId);
            List<Map.Entry<UUID, WorkGroup>> built = new ArrayList<>();
            for (var e : groups.entrySet()) {
                List<StorefrontRow> m = e.getValue();
                if (inStockOnly && m.stream().noneMatch(StorefrontRow::inStock)) {
                    continue;
                }
                Long from = m.stream().map(this::effective).filter(java.util.Objects::nonNull)
                        .min(java.util.Comparator.naturalOrder()).orElse(null);
                built.add(Map.entry(e.getKey(), new WorkGroup(m.stream().min(pick).orElseThrow(), m.size(), from,
                        m.stream().anyMatch(StorefrontRow::inStock),
                        m.stream().map(StorefrontRow::listedAt).max(java.util.Comparator.naturalOrder()).orElse(null))));
            }
            java.util.Comparator<Map.Entry<UUID, WorkGroup>> byKey = java.util.Map.Entry.comparingByKey();
            java.util.Comparator<Map.Entry<UUID, WorkGroup>> byFrom = java.util.Comparator
                    .comparing((Map.Entry<UUID, WorkGroup> e) -> e.getValue().priceFrom());
            switch (sort) {
                case NEWEST -> built.sort(java.util.Comparator
                        .comparing((Map.Entry<UUID, WorkGroup> e) -> e.getValue().listedAt()).reversed()
                        .thenComparing(byKey));
                case PRICE_ASC -> built.sort(byFrom.thenComparing(byKey));
                case PRICE_DESC -> built.sort(byFrom.thenComparing(byKey).reversed());
            }
            int from = Math.min(built.size(), page * size);
            return new PageResult<>(built.subList(from, Math.min(built.size(), from + size)).stream()
                    .map(Map.Entry::getValue).toList(), built.size(), page, size);
        }

        public List<StorefrontRow> findStorefrontRowsByWork(UUID t, UUID workId) {
            return items.values().stream().filter(StockItem::active).map(this::row)
                    .filter(r -> workId.equals(r.workId())).toList();
        }

        public void refreshWorkIds(UUID t, Map<UUID, UUID> works) {
            workWrites.add(Map.copyOf(works));
            works.forEach((id, w) -> {
                if (items.containsKey(id)) {
                    workSnapshots.put(id, w);
                }
            });
        }

        public List<UUID> titleIdsMissingCoverPrice(UUID t, int limit) {
            return items.values().stream().filter(StockItem::active)
                    .filter(i -> i.sellingPrice() == null && !coverSnapshots.containsKey(i.titleId()))
                    .map(StockItem::titleId).limit(limit).toList();
        }

        public void refreshCoverPrices(UUID t, Map<UUID, Long> prices) {
            if (failSnapshotWrites) {
                throw new IllegalStateException("db down");
            }
            snapshotWrites.add(Map.copyOf(prices));
            prices.forEach((id, v) -> {
                if (items.containsKey(id)) {
                    coverSnapshots.put(id, v);
                }
            });
        }

        public PageResult<StockMovement> ledger(UUID t, UUID titleId, int page, int size) {
            var all = ledger.stream().filter(m -> m.titleId().equals(titleId)).toList();
            return new PageResult<>(all, all.size(), page, size);
        }

        public LedgerTotals ledgerTotals(UUID t, UUID titleId) {
            long on = 0;
            long res = 0;
            for (StockMovement m : ledger) {
                if (m.titleId().equals(titleId)) {
                    on += m.deltaOnHand();
                    res += m.deltaReserved();
                }
            }
            return new LedgerTotals(on, res);
        }
    }

    private final class OrderAdapter implements SalesOrderRepository {
        public SalesOrder save(SalesOrder order) {
            if (raceWinner != null) {
                SalesOrder w = raceWinner;
                raceWinner = null;
                orders.put(w.id(), w);
                throw new DuplicateClientRequestException();
            }
            orders.put(order.id(), order);
            return order;
        }

        public Optional<SalesOrder> findById(UUID t, UUID id) {
            return Optional.ofNullable(orders.get(id));
        }

        public Optional<SalesOrder> findByClientRequestId(UUID t, UUID cid) {
            return orders.values().stream().filter(o -> cid.equals(o.clientRequestId())).findFirst();
        }

        public Optional<SalesOrder> findByIdAndCustomer(UUID t, UUID id, UUID customer) {
            return Optional.ofNullable(orders.get(id)).filter(o -> customer.equals(o.customerUserId()) && o.channel() == lms.sales.ordering.domain.OrderChannel.ONLINE);
        }

        public void lockLines(UUID t, UUID orderId) {
            lockedOrders.add(lockedOrders.size());
        }

        public List<UUID> findExpiredReservationIds(UUID t, Instant now, int limit) {
            return orders.values().stream().filter(o -> o.isReservationExpiredAt(now))
                    .sorted(java.util.Comparator.comparing(SalesOrder::reservationExpiresAt))
                    .limit(limit).map(SalesOrder::id).toList();
        }

        public Optional<SalesOrder> findByIdSkipLocked(UUID t, UUID id) {
            return Optional.ofNullable(orders.get(id));
        }

        public long countPendingOnline(UUID t, UUID customer) {
            return orders.values().stream().filter(o -> customer.equals(o.customerUserId())
                    && o.channel() == lms.sales.ordering.domain.OrderChannel.ONLINE
                    && (o.status() == lms.sales.ordering.domain.OrderStatus.PENDING_CONFIRMATION
                    || o.status() == lms.sales.ordering.domain.OrderStatus.AWAITING_PAYMENT)).count();
        }

        public long countCancelledOnlineSince(UUID t, UUID customer, Instant since) {
            return orders.values().stream().filter(o -> customer.equals(o.customerUserId())
                    && o.channel() == lms.sales.ordering.domain.OrderChannel.ONLINE
                    && o.status() == lms.sales.ordering.domain.OrderStatus.CANCELLED
                    && !o.createdAt().isBefore(since)).count();
        }

        public long countOnlineSince(UUID t, UUID customer, Instant since) {
            return orders.values().stream().filter(o -> customer.equals(o.customerUserId())
                    && o.channel() == lms.sales.ordering.domain.OrderChannel.ONLINE
                    && !o.createdAt().isBefore(since)).count();
        }

        public PageResult<SalesOrder> search(UUID t, Criteria c, int page, int size) {
            var all = orders.values().stream()
                    .filter(o -> c.customerUserId() == null || c.customerUserId().equals(o.customerUserId()))
                    .filter(o -> c.from() == null || !o.createdAt().isBefore(c.from()))
                    .filter(o -> c.to() == null || o.createdAt().isBefore(c.to()))
                    .filter(o -> c.status() == null || o.status() == c.status())
                    .filter(o -> c.paymentStatus() == null || o.paymentStatus() == c.paymentStatus())
                    .filter(o -> c.channel() == null || o.channel() == c.channel())
                    .filter(o -> c.keyword() == null || o.orderNo().contains(c.keyword())).toList();
            return new PageResult<>(all, all.size(), page, size);
        }
    }

    private final class ReturnAdapter implements SalesReturnRepository {
        public SalesReturn save(SalesReturn r) {
            if (returns.values().stream().anyMatch(x -> x.clientRequestId().equals(r.clientRequestId()))) {
                throw new DuplicateClientRequestException();
            }
            returns.put(r.id(), r);
            return r;
        }

        public Optional<SalesReturn> findById(UUID t, UUID id) {
            return Optional.ofNullable(returns.get(id));
        }

        public Optional<SalesReturn> findByClientRequestId(UUID t, UUID cid) {
            return returns.values().stream().filter(r -> cid.equals(r.clientRequestId())).findFirst();
        }

        public List<SalesReturn> findByOrderId(UUID t, UUID orderId) {
            return returns.values().stream().filter(r -> r.orderId().equals(orderId)).toList();
        }

        public Map<UUID, ReturnPlanner.Prior> priorByOrderLine(UUID t, UUID orderId) {
            Map<UUID, int[]> qty = new HashMap<>();
            Map<UUID, long[]> money = new HashMap<>();
            for (SalesReturn r : returns.values()) {
                if (r.orderId().equals(orderId)) {
                    for (ReturnLine l : r.lines()) {
                        qty.computeIfAbsent(l.orderLineId(), k -> new int[1])[0] += l.quantity();
                        money.computeIfAbsent(l.orderLineId(), k -> new long[1])[0] += l.refundAmount().amount();
                    }
                }
            }
            Map<UUID, ReturnPlanner.Prior> out = new HashMap<>();
            qty.forEach((k, v) -> out.put(k, new ReturnPlanner.Prior(v[0], money.get(k)[0])));
            return out;
        }
    }

    private final class RefundAdapter implements RefundRepository {
        public Refund save(Refund r) {
            refunds.put(r.id(), r);
            return r;
        }

        public Optional<Refund> findById(UUID t, UUID id) {
            return Optional.ofNullable(refunds.get(id));
        }

        public PageResult<Refund> search(UUID t, RefundStatus status, int page, int size) {
            var all = refunds.values().stream().filter(r -> status == null || r.status() == status).toList();
            return new PageResult<>(all, all.size(), page, size);
        }

        public List<Refund> findByOrderId(UUID t, UUID orderId) {
            return refunds.values().stream().filter(r -> r.orderId().equals(orderId)).toList();
        }
    }

    private final class SupplierAdapter implements SupplierRepository {
        public Supplier save(Supplier s) {
            suppliers.put(s.id(), s);
            return s;
        }

        public Optional<Supplier> findById(UUID t, UUID id) {
            return Optional.ofNullable(suppliers.get(id));
        }

        public boolean existsByName(UUID t, String name, UUID excludeId) {
            return suppliers.values().stream()
                    .anyMatch(s -> s.name().equalsIgnoreCase(name) && !s.id().equals(excludeId));
        }

        public PageResult<Supplier> search(UUID t, String keyword, int page, int size) {
            var all = suppliers.values().stream()
                    .filter(s -> keyword == null || s.name().toLowerCase().contains(keyword.toLowerCase())).toList();
            return new PageResult<>(all, all.size(), page, size);
        }
    }

    private final class ReceiptAdapter implements GoodsReceiptRepository {
        public GoodsReceipt save(GoodsReceipt r) {
            boolean isNew = !receipts.containsKey(r.id());
            if (isNew && receipts.values().stream().anyMatch(x -> r.clientRequestId().equals(x.clientRequestId()))) {
                throw new DuplicateClientRequestException();
            }
            receipts.put(r.id(), r);
            return r;
        }

        public Optional<GoodsReceipt> findById(UUID t, UUID id) {
            return Optional.ofNullable(receipts.get(id));
        }

        public Optional<GoodsReceipt> findByClientRequestId(UUID t, UUID cid) {
            return receipts.values().stream().filter(r -> cid.equals(r.clientRequestId())).findFirst();
        }

        public PageResult<GoodsReceipt> search(UUID t, ReceiptStatus st, UUID supplierId, int page, int size) {
            var all = receipts.values().stream().filter(r -> st == null || r.status() == st).toList();
            return new PageResult<>(all, all.size(), page, size);
        }
    }

    private final class SettingsAdapter implements SalesSettingsPort {
        public Optional<SalesSettings> find(UUID t) {
            return Optional.ofNullable(settings);
        }

        public SalesSettings save(SalesSettings s) {
            if (settings != null && settings.version() != s.version()) {
                throw new SalesException(ErrorCode.SETTINGS_VERSION_CONFLICT, "conflict");
            }
            settings = SalesSettings.restore(s.tenantId(), s.vatRateBps(), s.pricesIncludeVat(),
                    s.returnWindowDays(), s.fixedShippingFee(), s.lowStockThreshold(),
                    s.walkInPiiRetentionMonths(), s.version() + 1, s.updatedAt(), s.updatedBy());
            return settings;
        }
    }

    public static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        public void advance(java.time.Duration d) {
            advanceSeconds(d.toSeconds());
        }

        public void advanceSeconds(long s) {
            now = now.plusSeconds(s);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

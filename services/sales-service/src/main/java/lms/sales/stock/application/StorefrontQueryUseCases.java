package lms.sales.stock.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.CatalogLookupPort.CatalogTitle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Danh mục công khai cho storefront (ADR-0011 mục 2.6, ADR-0019): chỉ mặt hàng đã đưa vào bán và đang hoạt động. Chỉ
 * trả {@code inStock = available > 0}: KHÔNG bao giờ trả onHand, reserved, available, giá nhập, ngưỡng tồn, tenant_id,
 * ISBN. Một truy vấn trang mặt hàng + một lời gọi lô tới catalog (không N+1); trang tối đa 50.
 * <p>
 * Không có {@code @Transactional} ở mức use case: mỗi phương thức của {@link StockPort} tự có transaction riêng, nên lời
 * gọi HTTP sang catalog không giữ kết nối DB (xem {@link CatalogLookupPort}). Thứ tự: đọc DB, gọi catalog, ghi snapshot
 * giá (best-effort).
 */
public class StorefrontQueryUseCases {

    private static final Logger log = LoggerFactory.getLogger(StorefrontQueryUseCases.class);

    public static final int MAX_PAGE_SIZE = 50;
    public static final int DEFAULT_PAGE_SIZE = 20;
    /** Số đầu sách đồng bộ lười giá bìa mỗi lần trước truy vấn sắp theo giá (ADR-0019 2.1). */
    static final int SYNC_BATCH = 500;
    /** Số dòng tối đa tự sửa snapshot mỗi lần xem. */
    static final int HEAL_MAX = 50;

    public static final int SUGGEST_MIN_LENGTH = 2;
    public static final int SUGGEST_MAX_LENGTH = 50;
    public static final int SUGGEST_DEFAULT_LIMIT = 5;
    public static final int SUGGEST_MAX_LIMIT = 8;

    /** Mục danh sách: không có mô tả hay đoạn đọc thử. */
    public record ItemView(UUID titleId, String name, String author, long price, boolean inStock,
                           boolean hasPreview, String subtitle, Integer publicationYear, String edition,
                           String language, Integer pageCount, String format, String translator, String series,
                           Integer seriesNumber, String coverImageUrl, List<String> tags, UUID workId,
                           String workTitle, Integer variantCount, Long priceFrom) {
        public ItemView(UUID titleId, String name, String author, long price, boolean inStock, boolean hasPreview) {
            this(titleId, name, author, price, inStock, hasPreview, null, null, null, null, null, null, null, null,
                    null, null, List.of(), null, null, null, null);
        }
    }

    /** Một biến thể (ấn bản) của cùng tác phẩm ở trang chi tiết; đặt hàng bằng {@code titleId} của biến thể. */
    public record VariantView(UUID titleId, String format, String language, String translator, String edition,
                              Integer publicationYear, Integer partNumber, String partLabel, String coverImageUrl,
                              long price, boolean inStock) {
    }

    /** Chi tiết: như danh sách + mô tả (null nếu chưa nhập), không có previewText. */
    public record ItemDetailView(UUID titleId, String name, String author, long price, boolean inStock,
                                 boolean hasPreview, String description, String subtitle, Integer publicationYear,
                                 String edition, String language, Integer pageCount, String format,
                                 String translator, String series, Integer seriesNumber, String coverImageUrl,
                                 List<String> tags, UUID workId, String workTitle, List<VariantView> variants) {
        public ItemDetailView(UUID titleId, String name, String author, long price, boolean inStock,
                              boolean hasPreview, String description) {
            this(titleId, name, author, price, inStock, hasPreview, description, null, null, null, null, null, null,
                    null, null, null, null, List.of(), null, null, List.of());
        }
    }

    public record PreviewView(UUID titleId, String name, String previewText) {
    }

    /** Gợi ý tìm kiếm: đúng 3 trường, không giá, không tồn. */
    public record SuggestionView(UUID titleId, String name, String author) {
    }

    private final StockPort stock;
    private final CatalogLookupPort catalog;

    public StorefrontQueryUseCases(StockPort stock, CatalogLookupPort catalog) {
        this.stock = stock;
        this.catalog = catalog;
    }

    /**
     * @param sortParam {@code null} = mặc định theo ngữ cảnh (không từ khóa: newest; có từ khóa: theo tên); giá trị lạ
     *                  ném INVALID_REQUEST trước khi chạm DB
     * @param inStock   {@code TRUE} lọc còn hàng; {@code null}/{@code FALSE} không lọc
     */
    public PageResult<ItemView> list(UUID tenantId, String keyword, String sortParam, Boolean inStock, int page,
                                     int size) {
        return list(tenantId, keyword, sortParam, inStock, null, page, size);
    }

    /**
     * @param groupParam {@code null}/{@code EDITION}: mỗi ấn bản một thẻ (như cũ); {@code WORK}: gộp ấn bản cùng tác
     *                   phẩm (ADR-0026), thẻ mang ấn bản đại diện kèm {@code variantCount}, {@code priceFrom}; giá trị lạ
     *                   ném INVALID_REQUEST trước khi chạm DB
     */
    public PageResult<ItemView> list(UUID tenantId, String keyword, String sortParam, Boolean inStock,
                                     String groupParam, int page, int size) {
        StorefrontSort sort = StorefrontSort.parse(sortParam);
        StorefrontGroup group = StorefrontGroup.parse(groupParam);
        boolean inStockOnly = Boolean.TRUE.equals(inStock);
        int p = Math.max(0, page);
        int s = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        if (keyword == null || keyword.isBlank()) {
            StorefrontSort effective = sort == null ? StorefrontSort.NEWEST : sort;
            return group == StorefrontGroup.WORK ? listAllByWork(tenantId, effective, inStockOnly, p, s)
                    : listAll(tenantId, effective, inStockOnly, p, s);
        }
        return listByKeyword(tenantId, keyword.trim(), sort, inStockOnly, group == StorefrontGroup.WORK, p, s);
    }

    /** Gộp theo tác phẩm: một truy vấn gộp + một lời gọi lô tới catalog cho các ấn bản đại diện (không N+1). */
    private PageResult<ItemView> listAllByWork(UUID tenantId, StorefrontSort sort, boolean inStockOnly, int p,
                                               int s) {
        if (sort.byPrice()) {
            syncMissingCoverPrices(tenantId);
        }
        PageResult<StockPort.WorkGroup> groups = stock.listStorefrontByWork(tenantId, sort, inStockOnly, p, s);
        List<StorefrontRow> reps = groups.items().stream().map(StockPort.WorkGroup::representative).toList();
        Map<UUID, CatalogTitle> titles = reps.isEmpty() ? Map.of()
                : catalog.lookup(reps.stream().map(StorefrontRow::titleId).toList());
        List<ItemView> views = new ArrayList<>();
        for (StockPort.WorkGroup g : groups.items()) {
            StorefrontRow r = g.representative();
            CatalogTitle t = titles.get(r.titleId());
            Money price = t == null ? null : priceOf(r, t);
            if (price != null) {
                views.add(groupView(r, t, price.amount(), g.priceFrom() == null ? price.amount() : g.priceFrom(),
                        g.variantCount(), g.inStock()));
            }
        }
        healSnapshots(tenantId, reps, titles);
        return new PageResult<>(views, groups.total(), p, s);
    }

    private PageResult<ItemView> listAll(UUID tenantId, StorefrontSort sort, boolean inStockOnly, int p, int s) {
        if (sort.byPrice()) {
            syncMissingCoverPrices(tenantId);
        }
        PageResult<StorefrontRow> rows = stock.listStorefront(tenantId, sort, inStockOnly, p, s);
        Map<UUID, CatalogTitle> titles = rows.items().isEmpty() ? Map.of()
                : catalog.lookup(rows.items().stream().map(StorefrontRow::titleId).toList());
        List<ItemView> views = new ArrayList<>();
        for (StorefrontRow r : rows.items()) {
            CatalogTitle t = titles.get(r.titleId());
            if (t != null && priceOf(r, t) != null) {
                views.add(view(r, t));
            }
        }
        healSnapshots(tenantId, rows.items(), titles);
        return new PageResult<>(views, rows.total(), p, s);
    }

    /**
     * Tên/tác giả nằm ở catalog: lấy tối đa 200 đầu sách khớp rồi giao với mặt hàng đang bán, sắp và phân trang trong
     * bộ nhớ. Theo giá thì dùng giá SỐNG của catalog (chính xác), không dùng snapshot.
     */
    private PageResult<ItemView> listByKeyword(UUID tenantId, String keyword, StorefrontSort sort,
                                               boolean inStockOnly, boolean byWork, int p, int s) {
        List<CatalogTitle> matched = catalog.lookupByKeyword(keyword);
        Map<UUID, StorefrontRow> active = stock.findStorefrontRows(tenantId,
                matched.stream().map(CatalogTitle::id).toList());
        record Entry(StorefrontRow row, CatalogTitle title, ItemView view, java.time.Instant listedAt) {
        }
        List<Entry> all = new ArrayList<>();
        for (CatalogTitle t : matched) {
            StorefrontRow r = active.get(t.id());
            if (r != null && priceOf(r, t) != null && (!inStockOnly || r.inStock())) {
                all.add(new Entry(r, t, view(r, t), r.listedAt()));
            }
        }
        if (byWork) {
            all = collapseByWork(all.stream().map(e -> new Candidate<>(e.row(), e.title(), e)).toList(),
                    (e, v, listedAt) -> new Entry(e.row(), e.title(), v, listedAt));
        }
        Comparator<Entry> byName = Comparator.comparing(e -> lower(e.view().name()));
        Comparator<Entry> order;
        if (sort == null) {
            order = byName;
        } else if (sort == StorefrontSort.NEWEST) {
            Comparator<Entry> byListed = Comparator.comparing(e -> e.listedAt(),
                    Comparator.nullsLast(Comparator.<java.time.Instant>reverseOrder()));
            order = byListed.thenComparing(byName);
        } else {
            Comparator<Entry> byPrice = Comparator.comparingLong(e -> e.view().priceFrom() != null ? e.view().priceFrom() : e.view().price());
            order = (sort == StorefrontSort.PRICE_ASC ? byPrice : byPrice.reversed()).thenComparing(byName);
        }
        all.sort(order);
        int from = Math.min(all.size(), p * s);
        List<Entry> pageEntries = all.subList(from, Math.min(all.size(), from + s));
        Map<UUID, CatalogTitle> shown = new HashMap<>();
        pageEntries.forEach(e -> shown.put(e.title().id(), e.title()));
        healSnapshots(tenantId, pageEntries.stream().map(Entry::row).toList(), shown);
        return new PageResult<>(pageEntries.stream().map(Entry::view).toList(), all.size(), p, s);
    }

    /**
     * Thẻ sách theo danh sách id (carousel "nổi bật"): một truy vấn mặt hàng đang bán + một lời gọi lô tới catalog (không
     * N+1). Giữ thứ tự {@code ids}, id trùng gộp, bỏ qua id không còn bán hoặc không có giá hiệu lực. Chế độ EDITION.
     */
    public List<ItemView> listByIds(UUID tenantId, List<UUID> ids) {
        List<UUID> distinct = ids == null ? List.of() : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return List.of();
        }
        Map<UUID, StorefrontRow> rows = stock.findStorefrontRows(tenantId, distinct);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, CatalogTitle> titles = catalog.lookup(distinct.stream().filter(rows::containsKey).toList());
        List<ItemView> views = new ArrayList<>();
        List<StorefrontRow> shownRows = new ArrayList<>();
        for (UUID id : distinct) {
            StorefrontRow r = rows.get(id);
            CatalogTitle t = titles.get(id);
            if (r != null && t != null && priceOf(r, t) != null) {
                views.add(view(r, t));
                shownRows.add(r);
            }
        }
        healSnapshots(tenantId, shownRows, titles);
        return views;
    }

    public ItemDetailView get(UUID tenantId, UUID titleId) {
        StorefrontRow row = activeRow(tenantId, titleId);
        CatalogTitle t = catalog.lookup(List.of(titleId)).get(titleId);
        if (t == null || priceOf(row, t) == null) {
            throw notFound();
        }
        ItemView v = view(row, t);
        UUID workId = t.workId() != null ? t.workId() : row.workId();
        Map<UUID, StorefrontRow> rows = new LinkedHashMap<>();
        Map<UUID, CatalogTitle> titles = new HashMap<>();
        rows.put(titleId, row);
        titles.put(titleId, t);
        if (workId != null) {
            // Một truy vấn lấy cả nhóm + tối đa một lời gọi lô cho các ấn bản anh em (ADR-0026 3.6).
            List<StorefrontRow> siblings = stock.findStorefrontRowsByWork(tenantId, workId);
            siblings.forEach(r -> rows.putIfAbsent(r.titleId(), r));
            List<UUID> missing = rows.keySet().stream().filter(id -> !titles.containsKey(id)).toList();
            if (!missing.isEmpty()) {
                titles.putAll(catalog.lookup(missing));
            }
        }
        List<VariantView> variants = new ArrayList<>();
        for (StorefrontRow r : rows.values()) {
            CatalogTitle vt = titles.get(r.titleId());
            Money price = vt == null ? null : priceOf(r, vt);
            if (price != null) {
                variants.add(new VariantView(r.titleId(), vt.format(), vt.language(), vt.translator(), vt.edition(),
                        vt.publicationYear(), vt.partNumber(), vt.partLabel(), vt.coverImageUrl(), price.amount(),
                        r.inStock()));
            }
        }
        variants.sort(Comparator
                .comparing(VariantView::partNumber, Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
                .thenComparing(VariantView::publicationYear, Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
                .thenComparing(x -> lower(x.language())).thenComparing(x -> lower(x.format()))
                .thenComparing(VariantView::titleId));
        healSnapshots(tenantId, List.copyOf(rows.values()), titles);
        return new ItemDetailView(v.titleId(), v.name(), v.author(), v.price(), v.inStock(), v.hasPreview(),
                t.description(), t.subtitle(), t.publicationYear(), t.edition(), t.language(), t.pageCount(),
                t.format(), t.translator(), t.series(), t.seriesNumber(), t.coverImageUrl(), t.tags(), workId,
                t.workTitle(), variants);
    }

    public PreviewView preview(UUID tenantId, UUID titleId) {
        activeRow(tenantId, titleId);
        var p = catalog.preview(titleId).orElseThrow(StorefrontQueryUseCases::notFound);
        return new PreviewView(titleId, p.name(), p.previewText());
    }

    /**
     * Gợi ý tìm kiếm (ADR-0019 2.4): catalog tìm theo từ khóa (tối đa 200), giao với mặt hàng đang bán CÓ giá hiệu lực,
     * tên bắt đầu bằng {@code q} xếp trước rồi theo tên, cắt {@code limit}.
     */
    public List<SuggestionView> suggest(UUID tenantId, String q, Integer limit) {
        String query = q == null ? "" : q.trim();
        int length = query.codePointCount(0, query.length());
        if (length < SUGGEST_MIN_LENGTH || length > SUGGEST_MAX_LENGTH
                || query.codePoints().anyMatch(Character::isISOControl)) {
            throw SalesException.invalidRequest("q phải dài 2 đến 50 ký tự và không chứa ký tự điều khiển");
        }
        int max = limit == null || limit < 1 ? SUGGEST_DEFAULT_LIMIT : Math.min(limit, SUGGEST_MAX_LIMIT);
        List<CatalogTitle> matched = catalog.lookupByKeyword(query);
        if (matched.isEmpty()) {
            return List.of();
        }
        Map<UUID, StorefrontRow> active = stock.findStorefrontRows(tenantId,
                matched.stream().map(CatalogTitle::id).toList());
        String prefix = lower(query);
        List<CatalogTitle> hits = new ArrayList<>();
        for (CatalogTitle t : matched) {
            StorefrontRow r = active.get(t.id());
            if (r != null && priceOf(r, t) != null) {
                hits.add(t);
            }
        }
        Comparator<CatalogTitle> order = Comparator
                .comparing((CatalogTitle t) -> !lower(t.name()).startsWith(prefix))
                .thenComparing(t -> lower(t.name()));
        return hits.stream().sorted(order).limit(max)
                .map(t -> new SuggestionView(t.id(), t.name(), t.author())).toList();
    }

    /**
     * Đồng bộ lười (ADR-0019 2.1): lần đầu một tenant sắp theo giá, điền bản sao giá bìa cho tối đa 500 mặt hàng chưa có
     * giá hiệu lực. Best-effort: lỗi (catalog sập...) chỉ log, truy vấn vẫn chạy.
     */
    private void syncMissingCoverPrices(UUID tenantId) {
        try {
            List<UUID> missing = stock.titleIdsMissingCoverPrice(tenantId, SYNC_BATCH);
            if (missing.isEmpty()) {
                return;
            }
            Map<UUID, Long> prices = new LinkedHashMap<>();
            for (CatalogTitle t : catalog.lookup(missing).values()) {
                if (t.coverPrice() != null) {
                    prices.put(t.id(), Money.fromCoverPrice(t.coverPrice()).amount());
                }
            }
            stock.refreshCoverPrices(tenantId, prices);
        } catch (RuntimeException e) {
            log.warn("Không đồng bộ được giá bìa cho sắp xếp storefront: {}", e.toString());
        }
    }

    /** Tự sửa lệch snapshot với giá bìa sống (tối đa 50 dòng). Best-effort: lỗi chỉ log, không làm hỏng GET công khai. */
    private void healSnapshots(UUID tenantId, List<StorefrontRow> rows, Map<UUID, CatalogTitle> titles) {
        try {
            Map<UUID, Long> stale = new LinkedHashMap<>();
            for (StorefrontRow r : rows) {
                CatalogTitle t = titles.get(r.titleId());
                if (t == null || t.coverPrice() == null || stale.size() >= HEAL_MAX) {
                    continue;
                }
                long live = Money.fromCoverPrice(t.coverPrice()).amount();
                if (!Objects.equals(r.coverPriceSnapshot(), live)) {
                    stale.put(r.titleId(), live);
                }
            }
            if (!stale.isEmpty()) {
                stock.refreshCoverPrices(tenantId, stale);
            }
        } catch (RuntimeException e) {
            log.warn("Không tự sửa được snapshot giá bìa: {}", e.toString());
        }
        healWorkIds(tenantId, rows, titles);
    }

    /** Tự sửa snapshot work_id khi catalog trả workId khác (gộp/tách tác phẩm); catalog chưa biết tác phẩm thì giữ nguyên. */
    private void healWorkIds(UUID tenantId, List<StorefrontRow> rows, Map<UUID, CatalogTitle> titles) {
        try {
            Map<UUID, UUID> stale = new LinkedHashMap<>();
            for (StorefrontRow r : rows) {
                CatalogTitle t = titles.get(r.titleId());
                if (t != null && t.workId() != null && !t.workId().equals(r.workId()) && stale.size() < HEAL_MAX) {
                    stale.put(r.titleId(), t.workId());
                }
            }
            if (!stale.isEmpty()) {
                stock.refreshWorkIds(tenantId, stale);
            }
        } catch (RuntimeException e) {
            log.warn("Không tự sửa được snapshot work_id: {}", e.toString());
        }
    }

    private record Candidate<E>(StorefrontRow row, CatalogTitle title, E source) {
    }

    @FunctionalInterface
    private interface GroupFactory<E> {
        E build(E representative, ItemView view, java.time.Instant listedAt);
    }

    /**
     * Gộp trong bộ nhớ cho tìm theo từ khóa (catalog đã lọc tối đa 200 ấn bản): nhóm theo workId (catalog, rồi snapshot),
     * ấn bản đại diện = còn hàng, rẻ nhất, mới nhất. {@code variantCount} chỉ đếm ấn bản khớp từ khóa.
     */
    private static <E> List<E> collapseByWork(List<Candidate<E>> candidates, GroupFactory<E> factory) {
        Map<UUID, List<Candidate<E>>> groups = new LinkedHashMap<>();
        for (Candidate<E> c : candidates) {
            UUID key = c.title().workId() != null ? c.title().workId()
                    : c.row().workId() != null ? c.row().workId() : c.title().id();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(c);
        }
        Comparator<Candidate<E>> pick = Comparator
                .comparing((Candidate<E> c) -> c.row().inStock() ? 0 : 1)
                .thenComparingLong(c -> priceOf(c.row(), c.title()).amount())
                .thenComparing(c -> c.row().listedAt(), Comparator.nullsLast(Comparator.reverseOrder()));
        List<E> out = new ArrayList<>();
        for (List<Candidate<E>> members : groups.values()) {
            Candidate<E> rep = members.stream().min(pick).orElseThrow();
            long priceFrom = members.stream().mapToLong(c -> priceOf(c.row(), c.title()).amount()).min().orElseThrow();
            boolean inStock = members.stream().anyMatch(c -> c.row().inStock());
            java.time.Instant listedAt = members.stream().map(c -> c.row().listedAt()).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
            out.add(factory.build(rep.source(), groupView(rep.row(), rep.title(),
                    priceOf(rep.row(), rep.title()).amount(), priceFrom, members.size(), inStock), listedAt));
        }
        return out;
    }

    private StorefrontRow activeRow(UUID tenantId, UUID titleId) {
        StorefrontRow r = stock.findStorefrontRows(tenantId, List.of(titleId)).get(titleId);
        if (r == null) {
            throw notFound();
        }
        return r;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static SalesException notFound() {
        return new SalesException(ErrorCode.STOCK_ITEM_NOT_FOUND, "Không có mặt hàng");
    }

    private static Money priceOf(StorefrontRow r, CatalogTitle t) {
        if (r.sellingPrice() != null) {
            return r.sellingPrice();
        }
        return t.coverPrice() == null ? null : Money.fromCoverPrice(t.coverPrice());
    }

    /** Chế độ EDITION: không có variantCount/priceFrom (null), workId lấy từ catalog rồi snapshot. */
    private static ItemView view(StorefrontRow r, CatalogTitle t) {
        return new ItemView(r.titleId(), t.name(), t.author(), priceOf(r, t).amount(), r.inStock(), t.hasPreview(),
                t.subtitle(), t.publicationYear(), t.edition(), t.language(), t.pageCount(), t.format(),
                t.translator(), t.series(), t.seriesNumber(), t.coverImageUrl(), t.tags(),
                t.workId() != null ? t.workId() : r.workId(), t.workTitle(), null, null);
    }

    /** Chế độ WORK: thẻ của ấn bản đại diện, {@code inStock} là của cả nhóm, {@code price} là giá của đại diện. */
    private static ItemView groupView(StorefrontRow r, CatalogTitle t, long price, long priceFrom, int variantCount,
                                      boolean inStock) {
        return new ItemView(r.titleId(), t.name(), t.author(), price, inStock, t.hasPreview(),
                t.subtitle(), t.publicationYear(), t.edition(), t.language(), t.pageCount(), t.format(),
                t.translator(), t.series(), t.seriesNumber(), t.coverImageUrl(), t.tags(),
                t.workId() != null ? t.workId() : r.workId(), t.workTitle(), variantCount, priceFrom);
    }
}

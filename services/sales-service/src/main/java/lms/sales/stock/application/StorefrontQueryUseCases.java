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
                           Integer seriesNumber, String coverImageUrl, List<String> tags) {
        public ItemView(UUID titleId, String name, String author, long price, boolean inStock, boolean hasPreview) {
            this(titleId, name, author, price, inStock, hasPreview, null, null, null, null, null, null, null, null,
                    null, null, List.of());
        }
    }

    /** Chi tiết: như danh sách + mô tả (null nếu chưa nhập), không có previewText. */
    public record ItemDetailView(UUID titleId, String name, String author, long price, boolean inStock,
                                 boolean hasPreview, String description, String subtitle, Integer publicationYear,
                                 String edition, String language, Integer pageCount, String format,
                                 String translator, String series, Integer seriesNumber, String coverImageUrl,
                                 List<String> tags) {
        public ItemDetailView(UUID titleId, String name, String author, long price, boolean inStock,
                              boolean hasPreview, String description) {
            this(titleId, name, author, price, inStock, hasPreview, description, null, null, null, null, null, null,
                    null, null, null, null, List.of());
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
        StorefrontSort sort = StorefrontSort.parse(sortParam);
        boolean inStockOnly = Boolean.TRUE.equals(inStock);
        int p = Math.max(0, page);
        int s = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        if (keyword == null || keyword.isBlank()) {
            return listAll(tenantId, sort == null ? StorefrontSort.NEWEST : sort, inStockOnly, p, s);
        }
        return listByKeyword(tenantId, keyword.trim(), sort, inStockOnly, p, s);
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
                                               boolean inStockOnly, int p, int s) {
        List<CatalogTitle> matched = catalog.lookupByKeyword(keyword);
        Map<UUID, StorefrontRow> active = stock.findStorefrontRows(tenantId,
                matched.stream().map(CatalogTitle::id).toList());
        record Entry(StorefrontRow row, CatalogTitle title, ItemView view) {
        }
        List<Entry> all = new ArrayList<>();
        for (CatalogTitle t : matched) {
            StorefrontRow r = active.get(t.id());
            if (r != null && priceOf(r, t) != null && (!inStockOnly || r.inStock())) {
                all.add(new Entry(r, t, view(r, t)));
            }
        }
        Comparator<Entry> byName = Comparator.comparing(e -> lower(e.view().name()));
        Comparator<Entry> order;
        if (sort == null) {
            order = byName;
        } else if (sort == StorefrontSort.NEWEST) {
            Comparator<Entry> byListed = Comparator.comparing(e -> e.row().listedAt(),
                    Comparator.nullsLast(Comparator.<java.time.Instant>reverseOrder()));
            order = byListed.thenComparing(byName);
        } else {
            Comparator<Entry> byPrice = Comparator.comparingLong(e -> e.view().price());
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

    public ItemDetailView get(UUID tenantId, UUID titleId) {
        StorefrontRow row = activeRow(tenantId, titleId);
        CatalogTitle t = catalog.lookup(List.of(titleId)).get(titleId);
        if (t == null || priceOf(row, t) == null) {
            throw notFound();
        }
        ItemView v = view(row, t);
        healSnapshots(tenantId, List.of(row), Map.of(titleId, t));
        return new ItemDetailView(v.titleId(), v.name(), v.author(), v.price(), v.inStock(), v.hasPreview(),
                t.description(), t.subtitle(), t.publicationYear(), t.edition(), t.language(), t.pageCount(),
                t.format(), t.translator(), t.series(), t.seriesNumber(), t.coverImageUrl(), t.tags());
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

    private static ItemView view(StorefrontRow r, CatalogTitle t) {
        return new ItemView(r.titleId(), t.name(), t.author(), priceOf(r, t).amount(), r.inStock(), t.hasPreview(),
                t.subtitle(), t.publicationYear(), t.edition(), t.language(), t.pageCount(), t.format(),
                t.translator(), t.series(), t.seriesNumber(), t.coverImageUrl(), t.tags());
    }
}

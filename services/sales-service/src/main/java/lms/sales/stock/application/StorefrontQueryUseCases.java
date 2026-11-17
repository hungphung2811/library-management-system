package lms.sales.stock.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.CatalogLookupPort.CatalogTitle;
import lms.sales.stock.domain.StockItem;
import org.springframework.transaction.annotation.Transactional;

/**
 * Danh mục công khai cho storefront (ADR-0011 mục 2.6): chỉ mặt hàng đã đưa vào bán và đang hoạt động. Chỉ trả
 * {@code inStock = available > 0}: KHÔNG bao giờ trả onHand, reserved, available, giá nhập, ngưỡng tồn, tenant_id,
 * ISBN. Một truy vấn trang mặt hàng + một lời gọi lô tới catalog (không N+1); trang tối đa 50.
 */
public class StorefrontQueryUseCases {

    public static final int MAX_PAGE_SIZE = 50;
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** Mục danh sách: không có mô tả hay đoạn đọc thử. */
    public record ItemView(UUID titleId, String name, String author, long price, boolean inStock,
                           boolean hasPreview) {
    }

    /** Chi tiết: như danh sách + mô tả (null nếu chưa nhập), không có previewText. */
    public record ItemDetailView(UUID titleId, String name, String author, long price, boolean inStock,
                                 boolean hasPreview, String description) {
    }

    public record PreviewView(UUID titleId, String name, String previewText) {
    }

    private final StockPort stock;
    private final CatalogLookupPort catalog;

    public StorefrontQueryUseCases(StockPort stock, CatalogLookupPort catalog) {
        this.stock = stock;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public PageResult<ItemView> list(UUID tenantId, String keyword, int page, int size) {
        int p = Math.max(0, page);
        int s = size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        if (keyword == null || keyword.isBlank()) {
            PageResult<StockItem> items = stock.listActive(tenantId, p, s);
            Map<UUID, CatalogTitle> titles = items.items().isEmpty() ? Map.of()
                    : catalog.lookup(items.items().stream().map(StockItem::titleId).toList());
            List<ItemView> views = new ArrayList<>();
            for (StockItem i : items.items()) {
                CatalogTitle t = titles.get(i.titleId());
                if (t != null && priceOf(i, t) != null) {
                    views.add(view(i, t));
                }
            }
            return new PageResult<>(views, items.total(), p, s);
        }
        // Tên/tác giả nằm ở catalog: lấy tối đa 200 đầu sách khớp rồi giao với mặt hàng đang bán, phân trang tại đây
        List<CatalogTitle> matched = catalog.lookupByKeyword(keyword.trim());
        Map<UUID, StockItem> active = stock.findByTitleIds(tenantId, matched.stream().map(CatalogTitle::id).toList());
        List<ItemView> all = new ArrayList<>();
        for (CatalogTitle t : matched) {
            StockItem i = active.get(t.id());
            if (i != null && i.active() && priceOf(i, t) != null) {
                all.add(view(i, t));
            }
        }
        all.sort(Comparator.comparing(v -> v.name() == null ? "" : v.name().toLowerCase()));
        int from = Math.min(all.size(), p * s);
        return new PageResult<>(all.subList(from, Math.min(all.size(), from + s)), all.size(), p, s);
    }

    @Transactional(readOnly = true)
    public ItemDetailView get(UUID tenantId, UUID titleId) {
        StockItem item = activeItem(tenantId, titleId);
        CatalogTitle t = catalog.lookup(List.of(titleId)).get(titleId);
        if (t == null || priceOf(item, t) == null) {
            throw notFound();
        }
        ItemView v = view(item, t);
        return new ItemDetailView(v.titleId(), v.name(), v.author(), v.price(), v.inStock(), v.hasPreview(),
                t.description());
    }

    @Transactional(readOnly = true)
    public PreviewView preview(UUID tenantId, UUID titleId) {
        activeItem(tenantId, titleId);
        var p = catalog.preview(titleId).orElseThrow(StorefrontQueryUseCases::notFound);
        return new PreviewView(titleId, p.name(), p.previewText());
    }

    private StockItem activeItem(UUID tenantId, UUID titleId) {
        return stock.find(tenantId, titleId).filter(StockItem::active).orElseThrow(StorefrontQueryUseCases::notFound);
    }

    private static SalesException notFound() {
        return new SalesException(ErrorCode.STOCK_ITEM_NOT_FOUND, "Không có mặt hàng");
    }

    private static Money priceOf(StockItem i, CatalogTitle t) {
        if (i.sellingPrice() != null) {
            return i.sellingPrice();
        }
        return t.coverPrice() == null ? null : Money.fromCoverPrice(t.coverPrice());
    }

    private static ItemView view(StockItem i, CatalogTitle t) {
        return new ItemView(i.titleId(), t.name(), t.author(), priceOf(i, t).amount(), i.available() > 0,
                t.hasPreview());
    }
}

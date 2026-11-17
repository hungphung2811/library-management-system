package lms.sales.stock.application;

import java.util.UUID;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.shared.application.PageResult;
import lms.sales.stock.domain.StockItem;
import org.springframework.transaction.annotation.Transactional;

/**
 * Danh sách mặt hàng (lọc tồn thấp), tên đầu sách tra catalog theo lô.
 * Chưa có tìm theo từ khóa tên: tên nằm ở catalog, cần quyết định riêng.
 */
public class ListStockItemsUseCase {

    private final StockPort stock;
    private final CatalogLookupPort catalog;
    private final GetSalesSettingsUseCase settings;

    public ListStockItemsUseCase(StockPort stock, CatalogLookupPort catalog, GetSalesSettingsUseCase settings) {
        this.stock = stock;
        this.catalog = catalog;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public PageResult<StockItemView> execute(UUID tenantId, boolean lowStockOnly, int page, int size) {
        int threshold = settings.execute(tenantId).lowStockThreshold();
        PageResult<StockItem> items = stock.list(tenantId, lowStockOnly, threshold, page, size);
        var titles = catalog.lookup(items.items().stream().map(StockItem::titleId).toList());
        return items.map(i -> StockItemViews.of(i, titles, threshold));
    }
}

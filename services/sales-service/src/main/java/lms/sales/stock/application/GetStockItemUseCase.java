package lms.sales.stock.application;

import java.util.List;
import java.util.UUID;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.domain.StockItem;
import org.springframework.transaction.annotation.Transactional;

public class GetStockItemUseCase {

    private final StockPort stock;
    private final CatalogLookupPort catalog;
    private final GetSalesSettingsUseCase settings;

    public GetStockItemUseCase(StockPort stock, CatalogLookupPort catalog, GetSalesSettingsUseCase settings) {
        this.stock = stock;
        this.catalog = catalog;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public StockItemView execute(UUID tenantId, UUID titleId) {
        StockItem item = stock.find(tenantId, titleId)
                .orElseThrow(() -> new SalesException(ErrorCode.STOCK_ITEM_NOT_FOUND, "Không có mặt hàng"));
        int threshold = settings.execute(tenantId).lowStockThreshold();
        return StockItemViews.of(item, catalog.lookup(List.of(titleId)), threshold);
    }
}

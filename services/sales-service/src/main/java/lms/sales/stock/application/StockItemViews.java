package lms.sales.stock.application;

import java.util.Map;
import java.util.UUID;
import lms.sales.shared.domain.Money;
import lms.sales.stock.application.CatalogLookupPort.CatalogTitle;
import lms.sales.stock.domain.StockItem;

final class StockItemViews {

    private StockItemViews() {
    }

    static StockItemView of(StockItem item, Map<UUID, CatalogTitle> titles, int tenantThreshold) {
        CatalogTitle t = titles.get(item.titleId());
        Money price = item.sellingPrice() != null ? item.sellingPrice()
                : (t != null && t.coverPrice() != null ? Money.fromCoverPrice(t.coverPrice()) : null);
        return new StockItemView(item, t != null ? t.name() : null, price, item.isLowStock(tenantThreshold));
    }
}

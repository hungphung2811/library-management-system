package lms.sales.stock.application;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.domain.StockItem;
import org.springframework.transaction.annotation.Transactional;

/** Đưa đầu sách vào bán (tồn 0). Kiểm đầu sách tồn tại qua catalog (thao tác quản trị hiếm, gọi trong transaction). */
public class RegisterStockItemUseCase {

    private final StockPort stock;
    private final CatalogLookupPort catalog;
    private final GetSalesSettingsUseCase settings;
    private final SalesAuditPort audit;
    private final Clock clock;

    public RegisterStockItemUseCase(StockPort stock, CatalogLookupPort catalog, GetSalesSettingsUseCase settings,
                                    SalesAuditPort audit, Clock clock) {
        this.stock = stock;
        this.catalog = catalog;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public StockItemView execute(UUID tenantId, UUID userId, UUID titleId, Long sellingPrice,
                                 Integer lowStockThreshold) {
        var titles = catalog.lookup(List.of(titleId));
        if (!titles.containsKey(titleId)) {
            throw SalesException.titleNotAvailable(titleId);
        }
        if (stock.find(tenantId, titleId).isPresent()) {
            throw new SalesException(ErrorCode.STOCK_ITEM_EXISTS, "Đầu sách đã được đưa vào bán");
        }
        StockItem saved = stock.insert(StockItem.register(tenantId, titleId,
                sellingPrice == null ? null : Money.of(sellingPrice), lowStockThreshold));
        Map<String, Object> data = new HashMap<>();
        data.put("titleId", titleId.toString());
        if (sellingPrice != null) {
            data.put("sellingPrice", sellingPrice);
        }
        audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.STOCK_ITEM_REGISTERED,
                "StockItem", saved.id(), userId, clock.instant(), data));
        int threshold = settings.execute(tenantId).lowStockThreshold();
        return StockItemViews.of(saved, titles, threshold);
    }
}

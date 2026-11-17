package lms.sales.stock.application;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.domain.StockItem;
import org.springframework.transaction.annotation.Transactional;

/** Đổi giá bán; null = quay về giá bìa catalog. Không đụng coverPrice. */
public class UpdateStockItemPriceUseCase {

    private final StockPort stock;
    private final SalesAuditPort audit;
    private final Clock clock;

    public UpdateStockItemPriceUseCase(StockPort stock, SalesAuditPort audit, Clock clock) {
        this.stock = stock;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public StockItem execute(UUID tenantId, UUID userId, UUID titleId, Long sellingPrice) {
        StockItem item = stock.find(tenantId, titleId)
                .orElseThrow(() -> new SalesException(ErrorCode.STOCK_ITEM_NOT_FOUND, "Không có mặt hàng"));
        StockItem saved = stock.saveAttributes(item.withSellingPrice(sellingPrice == null ? null
                : Money.of(sellingPrice)));
        Map<String, Object> data = new HashMap<>();
        data.put("titleId", titleId.toString());
        data.put("oldPrice", item.sellingPrice() == null ? "COVER" : item.sellingPrice().amount());
        data.put("newPrice", sellingPrice == null ? "COVER" : sellingPrice);
        audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.PRICE_CHANGED, "StockItem",
                item.id(), userId, clock.instant(), data));
        return saved;
    }
}

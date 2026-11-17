package lms.sales.stock.application;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.domain.StockItem;
import org.springframework.transaction.annotation.Transactional;

/**
 * Kiểm kho: đếm thực tế onHand (phải >= reserved, vì hàng đang giữ vẫn nằm trên kệ), ghi STOCKTAKE bằng chênh lệch.
 * Đọc có khóa ghi để chênh lệch tính đúng so với số liệu hiện hành.
 */
public class StocktakeUseCase {

    private final StockPort stock;
    private final StockService stockService;
    private final SalesAuditPort audit;
    private final Clock clock;

    public StocktakeUseCase(StockPort stock, StockService stockService, SalesAuditPort audit, Clock clock) {
        this.stock = stock;
        this.stockService = stockService;
        this.audit = audit;
        this.clock = clock;
    }

    /** Trả chênh lệch (counted - onHand trước đó). */
    @Transactional
    public long execute(UUID tenantId, UUID userId, UUID titleId, long countedOnHand, String reason) {
        if (reason == null || reason.isBlank()) {
            throw SalesException.reasonRequired();
        }
        StockItem item = stock.findForUpdate(tenantId, titleId)
                .orElseThrow(() -> new SalesException(ErrorCode.STOCK_ITEM_NOT_FOUND, "Không có mặt hàng"));
        if (countedOnHand < 0 || countedOnHand < item.reserved()) {
            throw SalesException.invalidQuantity("Số đếm phải >= 0 và >= số đang giữ (" + item.reserved() + ")");
        }
        long delta = countedOnHand - item.onHand();
        stockService.applyStocktake(tenantId, userId, titleId, delta, reason.trim());
        audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.STOCKTAKE_RECORDED, "StockItem",
                item.id(), userId, clock.instant(),
                Map.of("titleId", titleId.toString(), "counted", countedOnHand, "delta", delta)));
        return delta;
    }
}

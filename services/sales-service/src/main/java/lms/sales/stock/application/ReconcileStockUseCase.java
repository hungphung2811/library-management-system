package lms.sales.stock.application;

import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.domain.StockItem;
import org.springframework.transaction.annotation.Transactional;

/** Đối soát bất biến: onHand = Σ deltaOnHand, reserved = Σ deltaReserved của sổ cái. */
public class ReconcileStockUseCase {

    private final StockPort stock;

    public ReconcileStockUseCase(StockPort stock) {
        this.stock = stock;
    }

    public record Result(UUID titleId, long onHand, long reserved, long ledgerOnHand, long ledgerReserved) {
        public boolean consistent() {
            return onHand == ledgerOnHand && reserved == ledgerReserved;
        }
    }

    @Transactional(readOnly = true)
    public Result execute(UUID tenantId, UUID titleId) {
        StockItem item = stock.find(tenantId, titleId)
                .orElseThrow(() -> new SalesException(ErrorCode.STOCK_ITEM_NOT_FOUND, "Không có mặt hàng"));
        var totals = stock.ledgerTotals(tenantId, titleId);
        return new Result(titleId, item.onHand(), item.reserved(), totals.onHand(), totals.reserved());
    }
}

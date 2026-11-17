package lms.sales.stock.application;

import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.domain.StockMovement;
import org.springframework.transaction.annotation.Transactional;

public class GetStockLedgerUseCase {

    private final StockPort stock;

    public GetStockLedgerUseCase(StockPort stock) {
        this.stock = stock;
    }

    @Transactional(readOnly = true)
    public PageResult<StockMovement> execute(UUID tenantId, UUID titleId, int page, int size) {
        if (stock.find(tenantId, titleId).isEmpty()) {
            throw new SalesException(ErrorCode.STOCK_ITEM_NOT_FOUND, "Không có mặt hàng");
        }
        return stock.ledger(tenantId, titleId, page, size);
    }
}

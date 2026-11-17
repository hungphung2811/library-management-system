package lms.sales.stock.application;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.SalesException;
import org.springframework.transaction.annotation.Transactional;

/** Điều chỉnh tồn tay (delta khác 0), bắt buộc lý do; ghi ADJUSTMENT vào sổ cái. */
public class AdjustStockUseCase {

    private final StockService stockService;
    private final SalesAuditPort audit;
    private final Clock clock;

    public AdjustStockUseCase(StockService stockService, SalesAuditPort audit, Clock clock) {
        this.stockService = stockService;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public void execute(UUID tenantId, UUID userId, UUID titleId, long delta, String reason) {
        if (reason == null || reason.isBlank()) {
            throw SalesException.reasonRequired();
        }
        stockService.adjust(tenantId, userId, titleId, delta, reason.trim());
        audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.STOCK_ADJUSTED, "StockItem",
                titleId, userId, clock.instant(), Map.of("titleId", titleId.toString(), "delta", delta)));
    }
}

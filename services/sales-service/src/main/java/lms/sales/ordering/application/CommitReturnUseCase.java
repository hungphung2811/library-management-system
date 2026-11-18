package lms.sales.ordering.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.sales.invoicing.application.InvoiceAdjustmentPort;
import lms.sales.ordering.domain.Disposition;
import lms.sales.ordering.domain.Refund;
import lms.sales.ordering.domain.ReturnLine;
import lms.sales.ordering.domain.ReturnPlanner;
import lms.sales.ordering.domain.SalesOrder;
import lms.sales.ordering.domain.SalesReturn;
import lms.sales.settings.application.GetSalesSettingsUseCase;
import lms.sales.shared.application.DocumentNumberPort;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.application.StockLine;
import lms.sales.stock.application.StockService;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phần transactional của phiếu trả: khóa dòng đơn (PESSIMISTIC_WRITE) rồi mới tính "đã mua - đã trả" nên hai phiếu
 * trả song song trên cùng đơn được tuần tự hóa và không trả vượt; cộng tồn (RESTOCK) hoặc ghi hao hụt (WRITE_OFF),
 * lưu phiếu, sinh khoản hoàn PENDING, audit, tất cả trong một transaction của sales_db.
 */
public class CommitReturnUseCase {

    private final SalesOrderRepository orders;
    private final SalesReturnRepository returns;
    private final RefundRepository refunds;
    private final StockService stockService;
    private final GetSalesSettingsUseCase settings;
    private final DocumentNumberPort numbers;
    private final SalesAuditPort audit;
    private final Clock clock;

    public CommitReturnUseCase(SalesOrderRepository orders, SalesReturnRepository returns, RefundRepository refunds,
                               StockService stockService, GetSalesSettingsUseCase settings,
                               DocumentNumberPort numbers, SalesAuditPort audit, Clock clock) {
        this.orders = orders;
        this.returns = returns;
        this.refunds = refunds;
        this.stockService = stockService;
        this.settings = settings;
        this.numbers = numbers;
        this.audit = audit;
        this.clock = clock;
    }

    /** Hóa đơn điều chỉnh khi trả hàng (ADR-0030 gói A). Mặc định không làm gì (test cũ, đơn không có hóa đơn). */
    private InvoiceAdjustmentPort invoiceAdjustments = InvoiceAdjustmentPort.NONE;

    public CommitReturnUseCase withInvoiceAdjustment(InvoiceAdjustmentPort port) {
        this.invoiceAdjustments = port == null ? InvoiceAdjustmentPort.NONE : port;
        return this;
    }

    @Transactional(readOnly = true)
    public Optional<ReturnResponse> replay(UUID tenantId, UUID clientRequestId) {
        return returns.findByClientRequestId(tenantId, clientRequestId).map(r -> toResponse(tenantId, r));
    }

    @Transactional
    public ReturnResponse execute(CreateReturnCommand cmd, boolean lateException) {
        UUID tenantId = cmd.tenantId();
        var existing = returns.findByClientRequestId(tenantId, cmd.clientRequestId());
        if (existing.isPresent()) {
            return toResponse(tenantId, existing.get());
        }
        orders.lockLines(tenantId, cmd.orderId());
        SalesOrder order = orders.findById(tenantId, cmd.orderId())
                .orElseThrow(() -> new SalesException(ErrorCode.ORDER_NOT_FOUND, "Không có đơn hàng"));
        Instant now = clock.instant();
        var prior = returns.priorByOrderLine(tenantId, order.id());
        var plan = ReturnPlanner.plan(order, prior, cmd.lines().stream()
                        .map(l -> new ReturnPlanner.RequestLine(l.orderLineId(), l.quantity(), l.disposition()))
                        .toList(), settings.execute(tenantId).returnWindowDays(), lateException, cmd.lateReason(),
                now);

        List<ReturnLine> lines = new ArrayList<>();
        List<StockLine> restock = new ArrayList<>();
        List<StockLine> writeOff = new ArrayList<>();
        for (var p : plan.lines()) {
            lines.add(new ReturnLine(UUID.randomUUID(), p.orderLineId(), p.titleId(), p.quantity(),
                    p.disposition(), p.refundAmount()));
            (p.disposition() == Disposition.RESTOCK ? restock : writeOff)
                    .add(new StockLine(p.titleId(), p.quantity()));
        }
        SalesReturn salesReturn = new SalesReturn(UUID.randomUUID(), tenantId,
                numbers.next(tenantId, DocumentNumberPort.RETURN), order.id(), cmd.reason().trim(), plan.late(),
                plan.late() ? cmd.lateReason().trim() : null, plan.daysOverdue(), cmd.clientRequestId(),
                cmd.actor(), now, lines);
        SalesReturn saved = returns.save(salesReturn);

        if (!restock.isEmpty()) {
            stockService.returnToStockAll(tenantId, cmd.actor(), restock, "RETURN", saved.id());
        }
        if (!writeOff.isEmpty()) {
            stockService.returnAndWriteOffAll(tenantId, cmd.actor(), writeOff, "RETURN", saved.id(),
                    "Trả hàng hỏng: " + saved.reason());
        }
        Money total = saved.refundTotal();
        UUID refundId = null;
        if (!total.isZero()) {
            Refund refund = refunds.save(Refund.forReturn(tenantId, order.id(), saved.id(), total, now));
            refundId = refund.id();
            audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.REFUND_CREATED, "Refund",
                    refund.id(), cmd.actor(), now, Map.of("orderId", order.id().toString(), "source", "RETURN",
                    "returnId", saved.id().toString(), "amount", total.amount())));
        }
        audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.RETURN_CREATED, "SalesReturn",
                saved.id(), cmd.actor(), now, Map.of("returnNo", saved.returnNo(), "orderId",
                order.id().toString(), "lines", saved.lines().size(), "refundTotal", total.amount(),
                "late", saved.late())));
        if (saved.late()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("returnNo", saved.returnNo());
            data.put("orderId", order.id().toString());
            data.put("daysOverdue", saved.daysOverdue());
            data.put("lateReason", saved.lateReason());
            audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.RETURN_LATE_APPROVED,
                    "SalesReturn", saved.id(), cmd.actor(), now, data));
        }
        // ADR-0030: đơn có hóa đơn còn hiệu lực thì sinh hóa đơn điều chỉnh giảm trong cùng transaction
        invoiceAdjustments.adjustForReturn(tenantId, order.id(), saved.id(), saved.lines().stream()
                .map(l -> new InvoiceAdjustmentPort.ReturnedLine(l.orderLineId(), l.quantity(),
                        l.refundAmount().amount())).toList(), cmd.actor(), now);
        return ReturnResponse.from(saved, refundId);
    }

    private ReturnResponse toResponse(UUID tenantId, SalesReturn r) {
        UUID refundId = refunds.findByOrderId(tenantId, r.orderId()).stream()
                .filter(f -> r.id().equals(f.returnId())).map(Refund::id).findFirst().orElse(null);
        return ReturnResponse.from(r, refundId);
    }
}

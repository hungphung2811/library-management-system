package lms.sales.stock.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.shared.application.PageResult;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.DuplicateClientRequestException;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.domain.GoodsReceipt;
import lms.sales.stock.domain.GoodsReceiptLine;
import lms.sales.stock.domain.ReceiptStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phiếu nhập kho: tạo nháp (idempotent theo clientRequestId), xác nhận (tăng tồn + sổ cái RECEIPT + audit trong một
 * transaction), hủy nháp, xem/tìm. Mỗi dòng phải là mặt hàng đã đưa vào bán.
 */
public class GoodsReceiptUseCases {

    public record LineData(UUID titleId, int quantity, long unitCost) {
    }

    private final GoodsReceiptCreator creator;
    private final GoodsReceiptRepository receipts;
    private final StockService stockService;
    private final SalesAuditPort audit;
    private final Clock clock;

    public GoodsReceiptUseCases(GoodsReceiptCreator creator, GoodsReceiptRepository receipts,
                                StockService stockService, SalesAuditPort audit, Clock clock) {
        this.creator = creator;
        this.receipts = receipts;
        this.stockService = stockService;
        this.audit = audit;
        this.clock = clock;
    }

    /** Không transactional: bắt trùng clientRequestId (hai request chạy đồng thời) rồi trả phiếu đã tạo. */
    public GoodsReceipt create(UUID tenantId, UUID userId, UUID supplierId, List<LineData> lines,
                               UUID clientRequestId) {
        try {
            return creator.execute(tenantId, userId, supplierId, lines, clientRequestId);
        } catch (DuplicateClientRequestException e) {
            return receipts.findByClientRequestId(tenantId, clientRequestId).orElseThrow(() -> e);
        }
    }

    @Transactional
    public GoodsReceipt confirm(UUID tenantId, UUID userId, UUID receiptId) {
        GoodsReceipt receipt = load(tenantId, receiptId);
        var now = clock.instant();
        receipt.confirm(userId, now); // DRAFT -> CONFIRMED, nếu không thì RECEIPT_STATE_CONFLICT
        stockService.receiveAll(tenantId, userId,
                receipt.lines().stream().map(l -> new StockLine(l.titleId(), l.quantity())).toList(),
                "GOODS_RECEIPT", receipt.id());
        GoodsReceipt saved = receipts.save(receipt);
        long totalQty = receipt.lines().stream().mapToLong(GoodsReceiptLine::quantity).sum();
        audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.GOODS_RECEIPT_CONFIRMED,
                "GoodsReceipt", saved.id(), userId, now,
                Map.of("totalQuantity", totalQty, "totalCost", receipt.totalCost().amount())));
        return saved;
    }

    @Transactional
    public GoodsReceipt cancel(UUID tenantId, UUID userId, UUID receiptId) {
        GoodsReceipt receipt = load(tenantId, receiptId);
        receipt.cancel();
        GoodsReceipt saved = receipts.save(receipt);
        audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.GOODS_RECEIPT_CANCELLED,
                "GoodsReceipt", saved.id(), userId, clock.instant(), Map.of()));
        return saved;
    }

    @Transactional(readOnly = true)
    public GoodsReceipt get(UUID tenantId, UUID receiptId) {
        return load(tenantId, receiptId);
    }

    @Transactional(readOnly = true)
    public PageResult<GoodsReceipt> search(UUID tenantId, ReceiptStatus status, UUID supplierId, int page,
                                           int size) {
        return receipts.search(tenantId, status, supplierId, page, size);
    }

    private GoodsReceipt load(UUID tenantId, UUID id) {
        return receipts.findById(tenantId, id)
                .orElseThrow(() -> new SalesException(ErrorCode.RECEIPT_NOT_FOUND, "Không có phiếu nhập"));
    }
}

package lms.sales.stock.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.shared.application.DocumentNumberPort;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;
import lms.sales.stock.domain.GoodsReceipt;
import lms.sales.stock.domain.GoodsReceiptLine;
import lms.sales.stock.domain.Supplier;
import org.springframework.transaction.annotation.Transactional;

/** Phần transactional của tạo phiếu nhập; bean riêng để {@link GoodsReceiptUseCases#create} bắt trùng ngoài transaction. */
public class GoodsReceiptCreator {

    private final GoodsReceiptRepository receipts;
    private final SupplierRepository suppliers;
    private final StockPort stock;
    private final DocumentNumberPort numbers;
    private final SalesAuditPort audit;
    private final Clock clock;

    public GoodsReceiptCreator(GoodsReceiptRepository receipts, SupplierRepository suppliers, StockPort stock,
                               DocumentNumberPort numbers, SalesAuditPort audit, Clock clock) {
        this.receipts = receipts;
        this.suppliers = suppliers;
        this.stock = stock;
        this.numbers = numbers;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public GoodsReceipt execute(UUID tenantId, UUID userId, UUID supplierId,
                                List<GoodsReceiptUseCases.LineData> lines, UUID clientRequestId) {
        if (clientRequestId == null) {
            throw SalesException.invalidRequest("Thiếu clientRequestId");
        }
        var existing = receipts.findByClientRequestId(tenantId, clientRequestId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Supplier supplier = suppliers.findById(tenantId, supplierId)
                .orElseThrow(() -> new SalesException(ErrorCode.SUPPLIER_NOT_FOUND, "Không có nhà cung cấp"));
        if (!supplier.active()) {
            throw new SalesException(ErrorCode.SUPPLIER_NOT_FOUND, "Nhà cung cấp đã ngừng hoạt động");
        }
        if (lines == null || lines.isEmpty()) {
            throw SalesException.invalidRequest("Phiếu nhập cần ít nhất một dòng");
        }
        List<GoodsReceiptLine> domainLines = lines.stream()
                .map(l -> new GoodsReceiptLine(l.titleId(), l.quantity(), Money.of(l.unitCost()))).toList();
        var items = stock.findByTitleIds(tenantId, domainLines.stream().map(GoodsReceiptLine::titleId).toList());
        for (GoodsReceiptLine l : domainLines) {
            if (!items.containsKey(l.titleId())) {
                throw SalesException.notRegistered(l.titleId());
            }
        }
        GoodsReceipt receipt = GoodsReceipt.draft(tenantId, numbers.next(tenantId, DocumentNumberPort.RECEIPT),
                supplierId, domainLines, clientRequestId, userId, clock.instant());
        GoodsReceipt saved = receipts.save(receipt);
        audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.GOODS_RECEIPT_CREATED,
                "GoodsReceipt", saved.id(), userId, clock.instant(), Map.of("lines", domainLines.size())));
        return saved;
    }
}

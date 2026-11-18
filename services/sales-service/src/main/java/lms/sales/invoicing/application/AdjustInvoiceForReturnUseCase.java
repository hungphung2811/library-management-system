package lms.sales.invoicing.application;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.invoicing.domain.AdjustmentPlanner;
import lms.sales.invoicing.domain.Invoice;
import lms.sales.shared.application.SalesAuditPort;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tự sinh hóa đơn điều chỉnh giảm khi phiếu trả hàng được lập (ADR-0030 D1.5), chạy trong transaction của
 * {@code CommitReturnUseCase} (khóa dòng đơn đã giữ, nên không đua với hủy/thay thế hóa đơn). Đơn không có hóa đơn còn
 * hiệu lực: không làm gì. Một phiếu trả tối đa một hóa đơn điều chỉnh (gọi lại là no-op). Người bán, người mua, mẫu số và
 * ký hiệu lấy từ hóa đơn gốc nên không phụ thuộc cấu hình hiện tại (phiếu trả không bao giờ bị chặn vì thiếu cấu hình).
 */
public class AdjustInvoiceForReturnUseCase implements InvoiceAdjustmentPort {

    private final InvoiceRepository invoices;
    private final InvoiceIssuing issuing;

    public AdjustInvoiceForReturnUseCase(InvoiceRepository invoices, InvoiceIssuerPort issuer, SalesAuditPort audit) {
        this.invoices = invoices;
        this.issuing = new InvoiceIssuing(issuer, invoices, audit);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void adjustForReturn(UUID tenantId, UUID orderId, UUID returnId, List<ReturnedLine> lines, UUID actor,
                                Instant now) {
        List<AdjustmentPlanner.Returned> refunded = lines.stream().filter(l -> l.refundAmount() > 0)
                .map(l -> new AdjustmentPlanner.Returned(l.orderLineId(), l.quantity(), l.refundAmount())).toList();
        if (refunded.isEmpty()) {
            return;
        }
        var effective = invoices.findEffectiveForOrder(tenantId, orderId);
        if (effective.isEmpty() || invoices.findAdjustmentByReturn(tenantId, returnId).isPresent()) {
            return;
        }
        Invoice original = effective.get();
        Map<UUID, AdjustmentPlanner.Prior> prior = new HashMap<>();
        for (Invoice adj : invoices.findActiveAdjustments(tenantId, original.id())) {
            adj.lines().forEach(l -> prior.merge(l.orderLineId(),
                    new AdjustmentPlanner.Prior(l.quantity(), l.lineTotal().amount()),
                    (a, b) -> new AdjustmentPlanner.Prior(a.quantity() + b.quantity(), a.amount() + b.amount())));
        }
        var planned = AdjustmentPlanner.plan(original.lines(), prior, refunded, original.pricesIncludeVat());
        Invoice draft = Invoice.draftAdjustment(UUID.randomUUID(), original, returnId, planned, actor, now);
        Invoice saved = issuing.issueAndSave(draft, actor, now);
        issuing.record(SalesAuditPort.Actions.INVOICE_ADJUSTED, saved, actor, now,
                Map.of("originalInvoiceId", original.id().toString(), "returnId", returnId.toString()));
    }
}

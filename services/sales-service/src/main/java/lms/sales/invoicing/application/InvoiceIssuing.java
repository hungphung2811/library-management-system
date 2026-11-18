package lms.sales.invoicing.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.invoicing.domain.Invoice;
import lms.sales.invoicing.domain.InvoiceLine;
import lms.sales.invoicing.domain.InvoiceNumbers;
import lms.sales.shared.application.SalesAuditPort;
import lms.sales.shared.domain.Money;

/**
 * Phần dùng chung của phát hành, thay thế, điều chỉnh (package-private): cấp số qua {@link InvoiceIssuerPort}, lưu hóa
 * đơn đã ISSUED và ghi audit. Phải chạy trong transaction của use case gọi (bộ đếm số cùng transaction).
 */
final class InvoiceIssuing {

    private final InvoiceIssuerPort issuer;
    private final InvoiceRepository invoices;
    private final SalesAuditPort audit;

    InvoiceIssuing(InvoiceIssuerPort issuer, InvoiceRepository invoices, SalesAuditPort audit) {
        this.issuer = issuer;
        this.invoices = invoices;
        this.audit = audit;
    }

    /** DRAFT sang ISSUED (số do bên phát hành cấp) rồi lưu một lần. */
    Invoice issueAndSave(Invoice draft, UUID actor, Instant now) {
        int year = InvoiceNumbers.fiscalYear(now);
        var result = issuer.issue(new InvoiceIssuerPort.IssueRequest(draft.tenantId(), draft.id(), draft.kind(),
                draft.templateCode(), draft.series(), year, draft.grandTotal().amount()));
        if (result.assignedNumber() == null) {
            // Nhà cung cấp bất đồng bộ (DRAFT rồi ISSUED ở transaction khác) chưa có cài đặt
            throw new IllegalStateException("Bên phát hành chưa cấp số hóa đơn");
        }
        draft.issue(result.assignedNumber(), year, result.issuer(), actor, now);
        if (result.externalId() != null || result.externalStatus() != null || result.externalLookupCode() != null) {
            draft.attachExternal(result.externalId(), result.externalStatus(), result.externalLookupCode());
        }
        return invoices.insert(draft);
    }

    /** Dữ liệu audit: chỉ id, số hóa đơn, ký hiệu, loại, số tiền (không dữ liệu cá nhân). */
    void record(String action, Invoice i, UUID actor, Instant now, Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("invoiceId", i.id().toString());
        if (i.invoiceNo() != null) {
            data.put("invoiceNo", i.displayNumber());
        }
        data.put("series", i.series());
        data.put("kind", i.kind().name());
        data.put("orderId", i.orderId().toString());
        data.put("grandTotal", i.grandTotal().amount());
        data.putAll(extra);
        audit.record(new SalesAuditPort.AuditEntry(i.tenantId(), action, "Invoice", i.id(), actor, now, data));
    }

    /** Dòng hóa đơn từ đơn: giá trị snapshot trên đơn (thuế suất sao vào từng dòng, phí ship là dòng không chịu thuế). */
    static List<InvoiceLine> linesOf(InvoiceOrder order) {
        List<InvoiceLine> lines = new ArrayList<>();
        int no = 1;
        long total = 0;
        for (InvoiceOrder.Line l : order.lines()) {
            long vat = l.vatAmount().amount();
            long before = l.lineTotal().amount() - vat;
            lines.add(new InvoiceLine(no++, l.orderLineId(), l.titleName(), l.isbn(), InvoiceLine.BOOK_UNIT,
                    l.quantity(), l.unitPrice(), l.discount(), Money.of(before), order.vatRateBps(),
                    l.vatAmount(), l.lineTotal()));
            total += l.lineTotal().amount();
        }
        if (!order.shippingFee().isZero()) {
            lines.add(InvoiceLine.shipping(no, order.shippingFee()));
            total += order.shippingFee().amount();
        }
        if (total != order.grandTotal().amount()) {
            throw new IllegalStateException("Tổng các dòng hóa đơn không khớp tổng đơn");
        }
        return lines;
    }
}

package lms.sales.invoicing.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.sales.invoicing.domain.BuyerInfo;
import lms.sales.invoicing.domain.Invoice;
import lms.sales.invoicing.domain.InvoiceLine;
import lms.sales.invoicing.domain.SellerInfo;

/**
 * Hóa đơn đầy đủ cho nhân viên (kèm dòng, hóa đơn điều chỉnh liên quan). Người mua là dữ liệu cá nhân nằm trong chính
 * hóa đơn (khóa quyền InvoiceController/get); {@code toString} không in. {@code notice} nhắc hóa đơn nội bộ chưa có giá trị
 * pháp lý của hóa đơn điện tử.
 */
public record InvoiceView(UUID id, String kind, String status, UUID orderId, String orderNo, UUID returnId,
                          UUID originalInvoiceId, String originalInvoiceNumber, String templateCode, String series,
                          Integer fiscalYear, Long invoiceNo, String invoiceNumber, Instant issuedAt, UUID issuedBy,
                          Instant cancelledAt, UUID cancelledBy, String cancelReason, SellerInfo seller,
                          BuyerInfo buyer, String currency, boolean pricesIncludeVat, String paymentMethod,
                          long totalBeforeVat, long vatTotal, long grandTotal, String amountInWords, String issuer,
                          String externalLookupCode, Instant createdAt, long version, List<LineView> lines,
                          List<AdjustmentRef> adjustments, String notice) {

    public static final String INTERNAL_NOTICE = "Hóa đơn nội bộ, không thay thế hóa đơn điện tử";

    public record LineView(int lineNo, String itemName, String isbn, String unit, int quantity, long unitPrice,
                           long discount, long amountBeforeVat, Integer vatRateBps, long vatAmount, long lineTotal) {
        static LineView from(InvoiceLine l) {
            return new LineView(l.lineNo(), l.itemName(), l.isbn(), l.unit(), l.quantity(), l.unitPrice().amount(),
                    l.discount().amount(), l.amountBeforeVat().amount(), l.vatRateBps(), l.vatAmount().amount(),
                    l.lineTotal().amount());
        }
    }

    /** Tham chiếu hóa đơn điều chỉnh của một hóa đơn gốc. */
    public record AdjustmentRef(UUID id, String invoiceNumber, long grandTotal, Instant issuedAt, UUID returnId) {
    }

    /** Dựng từ hóa đơn; {@code original} (nếu có) để ghi số của hóa đơn gốc, {@code adjustments} là các điều chỉnh còn hiệu lực. */
    public static InvoiceView of(Invoice i, Invoice original, List<Invoice> adjustments) {
        return new InvoiceView(i.id(), i.kind().name(), i.status().name(), i.orderId(), i.orderNo(), i.returnId(),
                i.originalInvoiceId(), original == null ? null : original.displayNumber(), i.templateCode(),
                i.series(), i.fiscalYear(), i.invoiceNo(), i.displayNumber(), i.issuedAt(), i.issuedBy(),
                i.cancelledAt(), i.cancelledBy(), i.cancelReason(), i.seller(), i.buyer(),
                lms.sales.shared.domain.Money.CURRENCY, i.pricesIncludeVat(), i.paymentMethod(),
                i.totalBeforeVat().amount(), i.vatTotal().amount(), i.grandTotal().amount(), i.amountInWords(),
                i.issuer(), i.externalLookupCode(), i.createdAt(), i.version(),
                i.lines().stream().map(LineView::from).toList(),
                adjustments.stream().map(a -> new AdjustmentRef(a.id(), a.displayNumber(), a.grandTotal().amount(),
                        a.issuedAt(), a.returnId())).toList(), INTERNAL_NOTICE);
    }

    @Override
    public String toString() {
        return "InvoiceView[" + id + ", " + kind + ", " + status + "]";
    }
}

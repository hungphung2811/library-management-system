package lms.sales.invoicing.application;

import java.time.Instant;
import java.util.UUID;

/** Dòng danh sách hóa đơn: không có dòng hàng, không địa chỉ/email người mua. */
public record InvoiceSummary(UUID id, String kind, String status, UUID orderId, String orderNo,
                             UUID originalInvoiceId, String templateCode, String series, Integer fiscalYear,
                             Long invoiceNo, String invoiceNumber, String buyerName, String buyerCompany,
                             String buyerTaxCode, long grandTotal, Instant issuedAt, Instant createdAt) {

    @Override
    public String toString() {
        return "InvoiceSummary[" + id + ", " + kind + ", " + status + "]";
    }
}

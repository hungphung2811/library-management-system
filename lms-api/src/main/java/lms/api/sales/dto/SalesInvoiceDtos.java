package lms.api.sales.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO của /api/sales/invoices/**, /api/sales/orders/{id}/invoice(-request) và /api/sales/invoice-settings (ADR-0030 gói A,
 * hóa đơn VAT NỘI BỘ: chưa có giá trị pháp lý của hóa đơn điện tử). Tiền là số nguyên đồng (long, VND); trường lạ từ
 * sales-service bị bỏ qua khi đọc. Thông tin người mua là dữ liệu cá nhân: {@code toString} không in.
 */
public final class SalesInvoiceDtos {

    private SalesInvoiceDtos() {
    }

    // ---- Đọc ----

    public record InvoiceSummaryView(UUID id, String kind, String status, UUID orderId, String orderNo,
                                     UUID originalInvoiceId, String templateCode, String series, Integer fiscalYear,
                                     Long invoiceNo, String invoiceNumber, String buyerName, String buyerCompany,
                                     String buyerTaxCode, long grandTotal, Instant issuedAt, Instant createdAt) {
    }

    public record SellerView(String legalName, String taxCode, String address, String phone, String email) {
    }

    public record BuyerView(String name, String company, String taxCode, String address, String email) {
        @Override
        public String toString() {
            return "BuyerView[***]";
        }
    }

    public record InvoiceLineView(int lineNo, String itemName, String isbn, String unit, int quantity, long unitPrice,
                                  long discount, long amountBeforeVat, Integer vatRateBps, long vatAmount,
                                  long lineTotal) {
    }

    public record InvoiceAdjustmentRef(UUID id, String invoiceNumber, long grandTotal, Instant issuedAt, UUID returnId) {
    }

    /** Hóa đơn đầy đủ. {@code notice} nhắc hóa đơn nội bộ chưa có giá trị pháp lý của hóa đơn điện tử. */
    public record InvoiceView(UUID id, String kind, String status, UUID orderId, String orderNo, UUID returnId,
                              UUID originalInvoiceId, String originalInvoiceNumber, String templateCode, String series,
                              Integer fiscalYear, Long invoiceNo, String invoiceNumber, Instant issuedAt, UUID issuedBy,
                              Instant cancelledAt, UUID cancelledBy, String cancelReason, SellerView seller,
                              BuyerView buyer, String currency, boolean pricesIncludeVat, String paymentMethod,
                              long totalBeforeVat, long vatTotal, long grandTotal, String amountInWords, String issuer,
                              String externalLookupCode, Instant createdAt, long version, List<InvoiceLineView> lines,
                              List<InvoiceAdjustmentRef> adjustments, String notice) {
    }

    public record InvoiceRequestView(UUID id, UUID orderId, String buyerName, String buyerCompany, String buyerTaxCode,
                                     String buyerAddress, String buyerEmail, String source, String status,
                                     Instant createdAt, Instant updatedAt, long version) {
        @Override
        public String toString() {
            return "InvoiceRequestView[order=" + orderId + "]";
        }
    }

    public record InvoiceSettingsView(String sellerLegalName, String sellerTaxCode, String sellerAddress,
                                      String sellerPhone, String sellerEmail, String templateCode, String series,
                                      boolean autoIssueOnCompletion, boolean complete, long version,
                                      Instant updatedAt) {
    }

    // ---- Ghi ----

    /** Tìm hóa đơn: {@code from} bao gồm, {@code to} loại trừ (ISO-8601); {@code page} bắt đầu từ 0. */
    public record InvoiceSearchQuery(String status, String kind, String from, String to, String keyword,
                                     Integer page, Integer size) {
    }

    public record IssueInvoiceRequest(@NotBlank @Size(max = 100) String clientRequestId) {
    }

    public record CancelInvoiceRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /** Thông tin người mua; có MST thì bắt buộc tên đơn vị và địa chỉ (sales-service kiểm). */
    public record BuyerPayload(@Size(max = 255) String name, @Size(max = 255) String company,
                               @Size(max = 14) String taxCode, @Size(max = 500) String address,
                               @Size(max = 255) String email) {
        @Override
        public String toString() {
            return "BuyerPayload[***]";
        }
    }

    /** {@code buyer} vắng thì giữ người mua của hóa đơn gốc. */
    public record ReplaceInvoiceRequest(@NotBlank @Size(max = 500) String reason, @Valid BuyerPayload buyer,
                                        @NotBlank @Size(max = 100) String clientRequestId) {
    }

    public record SaveInvoiceRequestRequest(@NotNull @Valid BuyerPayload buyer) {
        @Override
        public String toString() {
            return "SaveInvoiceRequestRequest[***]";
        }
    }

    public record UpdateInvoiceSettingsRequest(@NotBlank @Size(max = 255) String sellerLegalName,
                                               @NotBlank @Size(max = 14) String sellerTaxCode,
                                               @NotBlank @Size(max = 500) String sellerAddress,
                                               @Size(max = 30) String sellerPhone, @Size(max = 255) String sellerEmail,
                                               @NotBlank @Size(max = 6) String templateCode,
                                               @NotBlank @Size(max = 8) String series,
                                               @NotNull Boolean autoIssueOnCompletion, @NotNull Long version) {
    }

    /** Bản in HTML của hóa đơn (nội dung đã thoát HTML ở sales-service). */
    public record PrintedInvoice(String contentType, byte[] body) {
    }
}

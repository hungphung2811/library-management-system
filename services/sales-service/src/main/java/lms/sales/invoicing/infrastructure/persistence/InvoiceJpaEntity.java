package lms.sales.invoicing.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.sales.invoicing.domain.BuyerInfo;
import lms.sales.invoicing.domain.Invoice;
import lms.sales.invoicing.domain.InvoiceKind;
import lms.sales.invoicing.domain.InvoiceLine;
import lms.sales.invoicing.domain.InvoiceStatus;
import lms.sales.invoicing.domain.SellerInfo;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/**
 * Hóa đơn. Mọi cột là {@code updatable = false} trừ nhóm được phép đổi sau phát hành (trạng thái và cột hủy, cột tích hợp
 * external_*, version): cùng ràng buộc với trigger của DB (sales/V16).
 */
@Entity
@Table(name = "invoices")
class InvoiceJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "order_no", nullable = false, updatable = false)
    private String orderNo;

    @Column(name = "return_id", updatable = false)
    private UUID returnId;

    @Column(name = "original_invoice_id", updatable = false)
    private UUID originalInvoiceId;

    @Column(name = "template_code", nullable = false, updatable = false)
    private String templateCode;

    @Column(name = "series", nullable = false, updatable = false)
    private String series;

    @Column(name = "fiscal_year", updatable = false)
    private Integer fiscalYear;

    @Column(name = "invoice_no", updatable = false)
    private Long invoiceNo;

    @Column(name = "issued_at", updatable = false)
    private Instant issuedAt;

    @Column(name = "issued_by", updatable = false)
    private UUID issuedBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "seller_legal_name", nullable = false, updatable = false)
    private String sellerLegalName;

    @Column(name = "seller_tax_code", nullable = false, updatable = false)
    private String sellerTaxCode;

    @Column(name = "seller_address", nullable = false, updatable = false)
    private String sellerAddress;

    @Column(name = "seller_phone", updatable = false)
    private String sellerPhone;

    @Column(name = "seller_email", updatable = false)
    private String sellerEmail;

    @Column(name = "buyer_name", updatable = false)
    private String buyerName;

    @Column(name = "buyer_company", updatable = false)
    private String buyerCompany;

    @Column(name = "buyer_tax_code", updatable = false)
    private String buyerTaxCode;

    @Column(name = "buyer_address", updatable = false)
    private String buyerAddress;

    @Column(name = "buyer_email", updatable = false)
    private String buyerEmail;

    @Column(name = "currency", nullable = false, updatable = false)
    private String currency = "VND";

    @Column(name = "prices_include_vat", nullable = false, updatable = false)
    private boolean pricesIncludeVat;

    @Column(name = "payment_method", updatable = false)
    private String paymentMethod;

    @Column(name = "total_before_vat", nullable = false, updatable = false)
    private long totalBeforeVat;

    @Column(name = "vat_total", nullable = false, updatable = false)
    private long vatTotal;

    @Column(name = "grand_total", nullable = false, updatable = false)
    private long grandTotal;

    @Column(name = "amount_in_words", updatable = false)
    private String amountInWords;

    @Column(name = "issuer", nullable = false, updatable = false)
    private String issuer;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "external_status")
    private String externalStatus;

    @Column(name = "external_lookup_code")
    private String externalLookupCode;

    @Column(name = "client_request_id", updatable = false)
    private UUID clientRequestId;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected InvoiceJpaEntity() {
    }

    InvoiceJpaEntity(Invoice i) {
        this.tenantId = TenantContext.require();
        this.id = i.id();
        this.kind = i.kind().name();
        this.status = i.status().name();
        this.orderId = i.orderId();
        this.orderNo = i.orderNo();
        this.returnId = i.returnId();
        this.originalInvoiceId = i.originalInvoiceId();
        this.templateCode = i.templateCode();
        this.series = i.series();
        this.fiscalYear = i.fiscalYear();
        this.invoiceNo = i.invoiceNo();
        this.issuedAt = i.issuedAt();
        this.issuedBy = i.issuedBy();
        this.cancelledAt = i.cancelledAt();
        this.cancelledBy = i.cancelledBy();
        this.cancelReason = i.cancelReason();
        SellerInfo s = i.seller();
        this.sellerLegalName = s.legalName();
        this.sellerTaxCode = s.taxCode();
        this.sellerAddress = s.address();
        this.sellerPhone = s.phone();
        this.sellerEmail = s.email();
        BuyerInfo b = i.buyer();
        this.buyerName = b.name();
        this.buyerCompany = b.company();
        this.buyerTaxCode = b.taxCode();
        this.buyerAddress = b.address();
        this.buyerEmail = b.email();
        this.pricesIncludeVat = i.pricesIncludeVat();
        this.paymentMethod = i.paymentMethod();
        this.totalBeforeVat = i.totalBeforeVat().amount();
        this.vatTotal = i.vatTotal().amount();
        this.grandTotal = i.grandTotal().amount();
        this.amountInWords = i.amountInWords();
        this.issuer = i.issuer();
        this.externalId = i.externalId();
        this.externalStatus = i.externalStatus();
        this.externalLookupCode = i.externalLookupCode();
        this.clientRequestId = i.clientRequestId();
        this.createdBy = i.createdBy();
        this.createdAt = i.createdAt();
    }

    /** Ghi phần được đổi sau phát hành từ aggregate (hủy, cột tích hợp). */
    void applyMutable(Invoice i) {
        this.status = i.status().name();
        this.cancelledAt = i.cancelledAt();
        this.cancelledBy = i.cancelledBy();
        this.cancelReason = i.cancelReason();
        this.externalId = i.externalId();
        this.externalStatus = i.externalStatus();
        this.externalLookupCode = i.externalLookupCode();
    }

    Invoice toDomain(List<InvoiceLine> lines) {
        return Invoice.restore(new Invoice.Snapshot(id, tenantId, InvoiceKind.valueOf(kind),
                InvoiceStatus.valueOf(status), orderId, orderNo, returnId, originalInvoiceId, templateCode, series,
                fiscalYear, invoiceNo, issuedAt, issuedBy, cancelledAt, cancelledBy, cancelReason,
                new SellerInfo(sellerLegalName, sellerTaxCode, sellerAddress, sellerPhone, sellerEmail),
                new BuyerInfo(buyerName, buyerCompany, buyerTaxCode, buyerAddress, buyerEmail), pricesIncludeVat,
                paymentMethod, totalBeforeVat, vatTotal, grandTotal, amountInWords, issuer, externalId,
                externalStatus, externalLookupCode, clientRequestId, createdBy, createdAt, lines, version));
    }

    UUID getId() { return id; }
    UUID getTenantId() { return tenantId; }
    String getKind() { return kind; }
    String getStatus() { return status; }
    UUID getOrderId() { return orderId; }
    String getOrderNo() { return orderNo; }
    UUID getOriginalInvoiceId() { return originalInvoiceId; }
    String getTemplateCode() { return templateCode; }
    String getSeries() { return series; }
    Integer getFiscalYear() { return fiscalYear; }
    Long getInvoiceNo() { return invoiceNo; }
    Instant getIssuedAt() { return issuedAt; }
    Instant getCreatedAt() { return createdAt; }
    String getBuyerName() { return buyerName; }
    String getBuyerCompany() { return buyerCompany; }
    String getBuyerTaxCode() { return buyerTaxCode; }
    long getGrandTotal() { return grandTotal; }
    long getVersion() { return version; }
}

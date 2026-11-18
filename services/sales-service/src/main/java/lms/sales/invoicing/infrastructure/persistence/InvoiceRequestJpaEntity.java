package lms.sales.invoicing.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lms.sales.invoicing.domain.BuyerInfo;
import lms.sales.invoicing.domain.InvoiceRequest;
import lms.sales.invoicing.domain.RequestSource;
import lms.sales.invoicing.domain.RequestStatus;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/** Yêu cầu xuất hóa đơn (thông tin người mua là dữ liệu cá nhân). */
@Entity
@Table(name = "invoice_requests")
class InvoiceRequestJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "buyer_name")
    private String buyerName;

    @Column(name = "buyer_company")
    private String buyerCompany;

    @Column(name = "buyer_tax_code")
    private String buyerTaxCode;

    @Column(name = "buyer_address")
    private String buyerAddress;

    @Column(name = "buyer_email")
    private String buyerEmail;

    @Column(name = "source", nullable = false, updatable = false)
    private String source;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "requested_by", updatable = false)
    private UUID requestedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "pii_anonymized_at")
    private Instant piiAnonymizedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected InvoiceRequestJpaEntity() {
    }

    InvoiceRequestJpaEntity(InvoiceRequest r) {
        this.tenantId = TenantContext.require();
        this.id = r.id();
        this.orderId = r.orderId();
        this.source = r.source().name();
        this.requestedBy = r.requestedBy();
        this.createdAt = r.createdAt();
        apply(r);
    }

    void apply(InvoiceRequest r) {
        BuyerInfo b = r.buyer();
        this.buyerName = b.name();
        this.buyerCompany = b.company();
        this.buyerTaxCode = b.taxCode();
        this.buyerAddress = b.address();
        this.buyerEmail = b.email();
        this.status = r.status().name();
        this.updatedAt = r.updatedAt();
        this.piiAnonymizedAt = r.piiAnonymizedAt();
    }

    InvoiceRequest toDomain() {
        return InvoiceRequest.restore(id, tenantId, orderId,
                new BuyerInfo(buyerName, buyerCompany, buyerTaxCode, buyerAddress, buyerEmail),
                RequestSource.valueOf(source), RequestStatus.valueOf(status), requestedBy, createdAt, updatedAt,
                piiAnonymizedAt, version);
    }

    UUID getId() { return id; }
    long getVersion() { return version; }
}

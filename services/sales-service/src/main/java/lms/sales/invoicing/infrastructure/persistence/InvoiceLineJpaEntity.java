package lms.sales.invoicing.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lms.sales.invoicing.domain.InvoiceLine;
import lms.sales.shared.domain.Money;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/** Dòng hóa đơn (bất biến: không cột nào updatable, trigger DB chặn sửa/xóa khi hóa đơn không còn là nháp). */
@Entity
@Table(name = "invoice_lines")
class InvoiceLineJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private UUID invoiceId;

    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    @Column(name = "order_line_id", updatable = false)
    private UUID orderLineId;

    @Column(name = "item_name", nullable = false, updatable = false)
    private String itemName;

    @Column(name = "isbn", updatable = false)
    private String isbn;

    @Column(name = "unit", nullable = false, updatable = false)
    private String unit;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, updatable = false)
    private long unitPrice;

    @Column(name = "discount", nullable = false, updatable = false)
    private long discount;

    @Column(name = "amount_before_vat", nullable = false, updatable = false)
    private long amountBeforeVat;

    @Column(name = "vat_rate_bps", updatable = false)
    private Integer vatRateBps;

    @Column(name = "vat_amount", nullable = false, updatable = false)
    private long vatAmount;

    @Column(name = "line_total", nullable = false, updatable = false)
    private long lineTotal;

    protected InvoiceLineJpaEntity() {
    }

    InvoiceLineJpaEntity(UUID invoiceId, InvoiceLine l) {
        this.tenantId = TenantContext.require();
        this.id = UUID.randomUUID();
        this.invoiceId = invoiceId;
        this.lineNo = l.lineNo();
        this.orderLineId = l.orderLineId();
        this.itemName = l.itemName();
        this.isbn = l.isbn();
        this.unit = l.unit();
        this.quantity = l.quantity();
        this.unitPrice = l.unitPrice().amount();
        this.discount = l.discount().amount();
        this.amountBeforeVat = l.amountBeforeVat().amount();
        this.vatRateBps = l.vatRateBps();
        this.vatAmount = l.vatAmount().amount();
        this.lineTotal = l.lineTotal().amount();
    }

    InvoiceLine toDomain() {
        return new InvoiceLine(lineNo, orderLineId, itemName, isbn, unit, quantity, Money.of(unitPrice),
                Money.of(discount), Money.of(amountBeforeVat), vatRateBps, Money.of(vatAmount), Money.of(lineTotal));
    }

    UUID getInvoiceId() { return invoiceId; }
    int getLineNo() { return lineNo; }
}

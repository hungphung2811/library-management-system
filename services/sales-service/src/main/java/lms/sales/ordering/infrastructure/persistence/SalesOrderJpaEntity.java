package lms.sales.ordering.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lms.sales.ordering.domain.Discount;
import lms.sales.ordering.domain.Recipient;
import lms.sales.ordering.domain.SalesOrder;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/**
 * Đơn hàng (đầu đơn). Dòng ở SalesOrderLineJpaEntity, thanh toán ở PaymentRecordJpaEntity. walk_in_* và recipient_*
 * là dữ liệu cá nhân: toString không in. Các cột đổi theo trạng thái được {@link #apply} cập nhật từ aggregate.
 */
@Entity
@Table(name = "sales_orders")
class SalesOrderJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "order_no", nullable = false, updatable = false)
    private String orderNo;

    @Column(name = "channel", nullable = false, updatable = false)
    private String channel;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "payment_status", nullable = false)
    private String paymentStatus;

    @Column(name = "payment_method", nullable = false, updatable = false)
    private String paymentMethod;

    @Column(name = "customer_user_id", updatable = false)
    private UUID customerUserId;

    @Column(name = "walk_in_name")
    private String walkInName;

    @Column(name = "walk_in_phone")
    private String walkInPhone;

    @Column(name = "fulfilment", nullable = false, updatable = false)
    private String fulfilment;

    @Column(name = "recipient_name", updatable = false)
    private String recipientName;

    @Column(name = "recipient_phone", updatable = false)
    private String recipientPhone;

    @Column(name = "recipient_address", updatable = false)
    private String recipientAddress;

    @Column(name = "vat_rate_bps", nullable = false, updatable = false)
    private int vatRateBps;

    @Column(name = "prices_include_vat", nullable = false, updatable = false)
    private boolean pricesIncludeVat;

    @Column(name = "subtotal", nullable = false)
    private long subtotal;

    @Column(name = "discount_total", nullable = false)
    private long discountTotal;

    @Column(name = "vat_total", nullable = false)
    private long vatTotal;

    @Column(name = "shipping_fee", nullable = false)
    private long shippingFee;

    @Column(name = "grand_total", nullable = false)
    private long grandTotal;

    @Column(name = "order_discount_type")
    private String orderDiscountType;

    @Column(name = "order_discount_value")
    private Long orderDiscountValue;

    @Column(name = "client_request_id", nullable = false, updatable = false)
    private UUID clientRequestId;

    @Column(name = "exchange_of_return_id", updatable = false)
    private UUID exchangeOfReturnId;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "pii_anonymized_at")
    private Instant piiAnonymizedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected SalesOrderJpaEntity() {
    }

    SalesOrderJpaEntity(SalesOrder o) {
        this.tenantId = TenantContext.require();
        this.id = o.id();
        this.orderNo = o.orderNo();
        this.channel = o.channel().name();
        this.paymentMethod = o.paymentMethod().name();
        this.customerUserId = o.customerUserId();
        this.fulfilment = o.fulfilment().name();
        Recipient r = o.recipient();
        if (r != null) {
            this.recipientName = r.name();
            this.recipientPhone = r.phone();
            this.recipientAddress = r.address();
        }
        this.vatRateBps = o.vatRateBps();
        this.pricesIncludeVat = o.pricesIncludeVat();
        this.clientRequestId = o.clientRequestId();
        this.exchangeOfReturnId = o.exchangeOfReturnId();
        this.createdBy = o.createdBy();
        this.createdAt = o.createdAt();
        apply(o);
        // version để null: Hibernate khởi tạo 0 khi persist (version khác null bị coi là entity detached)
    }

    /** Sao chép các cột đổi theo trạng thái từ aggregate. */
    void apply(SalesOrder o) {
        this.status = o.status().name();
        this.paymentStatus = o.paymentStatus().name();
        this.walkInName = o.walkInName();
        this.walkInPhone = o.walkInPhone();
        this.subtotal = o.subtotal().amount();
        this.discountTotal = o.discountTotal().amount();
        this.vatTotal = o.vatTotal().amount();
        this.shippingFee = o.shippingFee().amount();
        this.grandTotal = o.grandTotal().amount();
        Discount d = o.orderDiscount();
        this.orderDiscountType = d == null ? null : d.type().name();
        this.orderDiscountValue = d == null ? null : d.value();
        this.confirmedAt = o.confirmedAt();
        this.confirmedBy = o.confirmedBy();
        this.paidAt = o.paidAt();
        this.completedAt = o.completedAt();
        this.cancelledAt = o.cancelledAt();
        this.cancelledBy = o.cancelledBy();
        this.cancelReason = o.cancelReason();
        this.piiAnonymizedAt = o.piiAnonymizedAt();
    }

    UUID getId() { return id; }
    UUID getTenantId() { return tenantId; }
    String getOrderNo() { return orderNo; }
    String getChannel() { return channel; }
    String getStatus() { return status; }
    String getPaymentStatus() { return paymentStatus; }
    String getPaymentMethod() { return paymentMethod; }
    UUID getCustomerUserId() { return customerUserId; }
    String getWalkInName() { return walkInName; }
    String getWalkInPhone() { return walkInPhone; }
    String getFulfilment() { return fulfilment; }
    String getRecipientName() { return recipientName; }
    String getRecipientPhone() { return recipientPhone; }
    String getRecipientAddress() { return recipientAddress; }
    int getVatRateBps() { return vatRateBps; }
    boolean isPricesIncludeVat() { return pricesIncludeVat; }
    long getSubtotal() { return subtotal; }
    long getDiscountTotal() { return discountTotal; }
    long getVatTotal() { return vatTotal; }
    long getShippingFee() { return shippingFee; }
    long getGrandTotal() { return grandTotal; }
    String getOrderDiscountType() { return orderDiscountType; }
    Long getOrderDiscountValue() { return orderDiscountValue; }
    UUID getClientRequestId() { return clientRequestId; }
    UUID getExchangeOfReturnId() { return exchangeOfReturnId; }
    UUID getCreatedBy() { return createdBy; }
    Instant getCreatedAt() { return createdAt; }
    Instant getConfirmedAt() { return confirmedAt; }
    UUID getConfirmedBy() { return confirmedBy; }
    Instant getPaidAt() { return paidAt; }
    Instant getCompletedAt() { return completedAt; }
    Instant getCancelledAt() { return cancelledAt; }
    UUID getCancelledBy() { return cancelledBy; }
    String getCancelReason() { return cancelReason; }
    Instant getPiiAnonymizedAt() { return piiAnonymizedAt; }
    long getVersion() { return version; }

    /** Không in dữ liệu cá nhân. */
    @Override
    public String toString() {
        return "SalesOrderJpaEntity[" + orderNo + ", " + status + "]";
    }
}

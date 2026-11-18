package lms.sales.ordering.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lms.sales.ordering.domain.PaymentIntent;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/** Lệnh thanh toán QR (ADR-0020); trạng thái, mã giao dịch và thời điểm thu đổi theo máy trạng thái, khóa lạc quan @Version. */
@Entity
@Table(name = "payment_intents")
class PaymentIntentJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "amount", nullable = false, updatable = false)
    private long amount;

    @Column(name = "reference", nullable = false, updatable = false)
    private String reference;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "provider", nullable = false, updatable = false)
    private String provider;

    @Column(name = "provider_txn_id")
    private String providerTxnId;

    @Column(name = "qr_payload", updatable = false)
    private String qrPayload;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "tendered_amount")
    private Long tenderedAmount;

    @Column(name = "change_due")
    private Long changeDue;

    @Column(name = "transaction_ref")
    private String transactionRef;

    /** Ca thu ngân của người tạo đơn lúc tạo lệnh (ADR-0030), không đổi sau đó. */
    @Column(name = "shift_id", updatable = false)
    private UUID shiftId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected PaymentIntentJpaEntity() {
    }

    PaymentIntentJpaEntity(PaymentIntent i) {
        this.tenantId = TenantContext.require();
        this.id = i.id();
        this.orderId = i.orderId();
        this.amount = i.amount().amount();
        this.reference = i.reference();
        this.provider = i.provider();
        this.qrPayload = i.qrPayload();
        this.expiresAt = i.expiresAt();
        this.createdAt = i.createdAt();
        this.shiftId = i.shiftId();
        apply(i);
        // version để null: Hibernate khởi tạo 0 khi persist
    }

    void apply(PaymentIntent i) {
        this.status = i.status().name();
        this.providerTxnId = i.providerTxnId();
        this.paidAt = i.paidAt();
        this.tenderedAmount = i.tenderedAmount();
        this.changeDue = i.changeDue();
        this.transactionRef = i.transactionRef();
    }

    UUID getId() { return id; }
    UUID getTenantId() { return tenantId; }
    UUID getOrderId() { return orderId; }
    long getAmount() { return amount; }
    String getReference() { return reference; }
    String getStatus() { return status; }
    String getProvider() { return provider; }
    String getProviderTxnId() { return providerTxnId; }
    String getQrPayload() { return qrPayload; }
    Instant getExpiresAt() { return expiresAt; }
    Instant getPaidAt() { return paidAt; }
    Instant getCreatedAt() { return createdAt; }
    Long getTenderedAmount() { return tenderedAmount; }
    Long getChangeDue() { return changeDue; }
    String getTransactionRef() { return transactionRef; }
    UUID getShiftId() { return shiftId; }
    long getVersion() { return version == null ? 0 : version; }
}

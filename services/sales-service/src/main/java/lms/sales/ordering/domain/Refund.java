package lms.sales.ordering.domain;

import java.time.Instant;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.SalesException;

/** Khoản hoàn tiền (bản ghi riêng): chờ hoàn (PENDING) rồi đã hoàn (REFUNDED) do nhân viên có khóa đánh dấu. */
public final class Refund {

    private final UUID id;
    private final UUID tenantId;
    private final UUID orderId;
    private final RefundSource source;
    private final UUID returnId;
    private final Money amount;
    private RefundStatus status;
    private PaymentMethod method;
    private String reference;
    private final Instant createdAt;
    private UUID refundedBy;
    private Instant refundedAt;
    private final long version;

    private Refund(UUID id, UUID tenantId, UUID orderId, RefundSource source, UUID returnId, Money amount,
                   RefundStatus status, PaymentMethod method, String reference, Instant createdAt,
                   UUID refundedBy, Instant refundedAt, long version) {
        if (amount.isZero()) {
            throw SalesException.invalidPrice("Khoản hoàn phải lớn hơn 0");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.orderId = orderId;
        this.source = source;
        this.returnId = returnId;
        this.amount = amount;
        this.status = status;
        this.method = method;
        this.reference = reference;
        this.createdAt = createdAt;
        this.refundedBy = refundedBy;
        this.refundedAt = refundedAt;
        this.version = version;
    }

    public static Refund forCancellation(UUID tenantId, UUID orderId, Money amount, Instant now) {
        return new Refund(UUID.randomUUID(), tenantId, orderId, RefundSource.CANCELLATION, null, amount,
                RefundStatus.PENDING, null, null, now, null, null, 0);
    }

    public static Refund forReturn(UUID tenantId, UUID orderId, UUID returnId, Money amount, Instant now) {
        return new Refund(UUID.randomUUID(), tenantId, orderId, RefundSource.RETURN, returnId, amount,
                RefundStatus.PENDING, null, null, now, null, null, 0);
    }

    public static Refund restore(UUID id, UUID tenantId, UUID orderId, RefundSource source, UUID returnId,
                                 Money amount, RefundStatus status, PaymentMethod method, String reference,
                                 Instant createdAt, UUID refundedBy, Instant refundedAt, long version) {
        return new Refund(id, tenantId, orderId, source, returnId, amount, status, method, reference, createdAt,
                refundedBy, refundedAt, version);
    }

    /** Chỉ từ PENDING; đã hoàn rồi thì 409 ORDER_STATE_CONFLICT. */
    public void markRefunded(PaymentMethod method, String reference, UUID by, Instant now) {
        if (status != RefundStatus.PENDING) {
            throw new SalesException(ErrorCode.ORDER_STATE_CONFLICT, "Khoản hoàn đã được xử lý");
        }
        if (method == null) {
            throw SalesException.invalidRequest("Thiếu phương thức hoàn tiền");
        }
        this.status = RefundStatus.REFUNDED;
        this.method = method;
        this.reference = reference == null || reference.isBlank() ? null : reference.trim();
        this.refundedBy = by;
        this.refundedAt = now;
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID orderId() { return orderId; }
    public RefundSource source() { return source; }
    public UUID returnId() { return returnId; }
    public Money amount() { return amount; }
    public RefundStatus status() { return status; }
    public PaymentMethod method() { return method; }
    public String reference() { return reference; }
    public Instant createdAt() { return createdAt; }
    public UUID refundedBy() { return refundedBy; }
    public Instant refundedAt() { return refundedAt; }
    public long version() { return version; }
}

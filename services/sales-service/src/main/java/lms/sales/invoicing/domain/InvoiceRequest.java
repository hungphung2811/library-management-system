package lms.sales.invoicing.domain;

import java.time.Instant;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;

/**
 * Yêu cầu xuất hóa đơn của một đơn (mỗi đơn tối đa một). OPEN thì sửa được thông tin người mua; FULFILLED (đã có hóa đơn)
 * thì khóa, muốn đổi thì thay thế hóa đơn. Thông tin người mua là dữ liệu cá nhân: không in, không vào audit/log.
 */
public final class InvoiceRequest {

    private final UUID id;
    private final UUID tenantId;
    private final UUID orderId;
    private BuyerInfo buyer;
    private final RequestSource source;
    private RequestStatus status;
    private final UUID requestedBy;
    private final Instant createdAt;
    private Instant updatedAt;
    private final Instant piiAnonymizedAt;
    private final long version;

    private InvoiceRequest(UUID id, UUID tenantId, UUID orderId, BuyerInfo buyer, RequestSource source,
                           RequestStatus status, UUID requestedBy, Instant createdAt, Instant updatedAt,
                           Instant piiAnonymizedAt, long version) {
        this.id = id;
        this.tenantId = tenantId;
        this.orderId = orderId;
        this.buyer = buyer;
        this.source = source;
        this.status = status;
        this.requestedBy = requestedBy;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.piiAnonymizedAt = piiAnonymizedAt;
        this.version = version;
    }

    public static InvoiceRequest open(UUID tenantId, UUID orderId, BuyerInfo buyer, RequestSource source,
                                      UUID requestedBy, Instant now) {
        return new InvoiceRequest(UUID.randomUUID(), tenantId, orderId, buyer.requireIdentified(), source,
                RequestStatus.OPEN, requestedBy, now, now, null, 0);
    }

    public static InvoiceRequest restore(UUID id, UUID tenantId, UUID orderId, BuyerInfo buyer,
                                         RequestSource source, RequestStatus status, UUID requestedBy,
                                         Instant createdAt, Instant updatedAt, Instant piiAnonymizedAt,
                                         long version) {
        return new InvoiceRequest(id, tenantId, orderId, buyer, source, status, requestedBy, createdAt, updatedAt,
                piiAnonymizedAt, version);
    }

    /** Thay thông tin người mua (chỉ khi còn OPEN). */
    public void update(BuyerInfo newBuyer, Instant now) {
        if (status != RequestStatus.OPEN) {
            throw new SalesException(ErrorCode.INVOICE_ALREADY_EXISTS,
                    "Đã có hóa đơn cho đơn này: dùng thay thế hóa đơn để đổi thông tin người mua");
        }
        this.buyer = newBuyer.requireIdentified();
        this.updatedAt = now;
    }

    /** Đã xuất hóa đơn. */
    public void fulfill(Instant now) {
        if (status == RequestStatus.OPEN) {
            this.status = RequestStatus.FULFILLED;
            this.updatedAt = now;
        }
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public UUID orderId() { return orderId; }
    public BuyerInfo buyer() { return buyer; }
    public RequestSource source() { return source; }
    public RequestStatus status() { return status; }
    public UUID requestedBy() { return requestedBy; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant piiAnonymizedAt() { return piiAnonymizedAt; }
    public long version() { return version; }

    @Override
    public String toString() {
        return "InvoiceRequest[order=" + orderId + ", " + status + "]";
    }
}

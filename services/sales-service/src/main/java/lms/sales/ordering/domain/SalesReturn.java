package lms.sales.ordering.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.sales.shared.domain.Money;

/** Phiếu trả hàng liên kết đơn gốc. Bất biến sau khi tạo. Phiếu quá hạn lưu lý do và người cho phép (createdBy). */
public final class SalesReturn {

    private final UUID id;
    private final UUID tenantId;
    private final String returnNo;
    private final UUID orderId;
    private final String reason;
    private final boolean late;
    private final String lateReason;
    private final int daysOverdue;
    private final UUID clientRequestId;
    private final UUID createdBy;
    private final Instant createdAt;
    private final List<ReturnLine> lines;

    public SalesReturn(UUID id, UUID tenantId, String returnNo, UUID orderId, String reason, boolean late,
                       String lateReason, int daysOverdue, UUID clientRequestId, UUID createdBy, Instant createdAt,
                       List<ReturnLine> lines) {
        this.id = id;
        this.tenantId = tenantId;
        this.returnNo = returnNo;
        this.orderId = orderId;
        this.reason = reason;
        this.late = late;
        this.lateReason = lateReason;
        this.daysOverdue = daysOverdue;
        this.clientRequestId = clientRequestId;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.lines = List.copyOf(lines);
    }

    public Money refundTotal() {
        long sum = 0;
        for (ReturnLine l : lines) {
            sum += l.refundAmount().amount();
        }
        return Money.of(sum);
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public String returnNo() { return returnNo; }
    public UUID orderId() { return orderId; }
    public String reason() { return reason; }
    public boolean late() { return late; }
    public String lateReason() { return lateReason; }
    public int daysOverdue() { return daysOverdue; }
    public UUID clientRequestId() { return clientRequestId; }
    public UUID createdBy() { return createdBy; }
    public Instant createdAt() { return createdAt; }
    public List<ReturnLine> lines() { return lines; }
}

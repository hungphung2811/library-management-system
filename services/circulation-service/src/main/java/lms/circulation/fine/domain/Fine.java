package lms.circulation.fine.domain;

import lms.common.kernel.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Aggregate Root: một khoản phí phạt của độc giả cho một bản sao trong một lượt mượn. UNPAID -> PAID (UC09). */
public class Fine {

    private final UUID id;
    private final UUID readerId;
    private final UUID borrowingId;
    private final UUID bookCopyId;
    private final FineReason reason;
    private final BigDecimal amount;
    private FineStatus status;
    private final Instant createdAt;
    private Instant paidAt;
    /** Tenant sở hữu, chỉ có khi dựng lại từ persistence (đọc); khoản mới lập chưa có (null). Không tham gia nghiệp vụ. */
    private UUID tenantId;
    private final List<DomainEvent> events = new ArrayList<>();

    private Fine(UUID id, UUID readerId, UUID borrowingId, UUID bookCopyId, FineReason reason, BigDecimal amount,
                 FineStatus status, Instant createdAt, Instant paidAt) {
        if (id == null || readerId == null || borrowingId == null || bookCopyId == null) {
            throw new InvalidFineException("Khoản phạt thiếu định danh độc giả/lượt mượn/bản sao");
        }
        if (reason == null) throw new InvalidFineException("Thiếu lý do phạt");
        if (amount == null || amount.signum() <= 0) throw new InvalidFineException("Số tiền phạt phải > 0");
        if (status == null || createdAt == null) throw new InvalidFineException("Thiếu trạng thái hoặc thời điểm lập");
        if ((status == FineStatus.PAID) != (paidAt != null)) throw new InvalidFineException("Thời điểm thu không khớp trạng thái");
        this.id = id;
        this.readerId = readerId;
        this.borrowingId = borrowingId;
        this.bookCopyId = bookCopyId;
        this.reason = reason;
        this.amount = amount;
        this.status = status;
        this.createdAt = createdAt;
        this.paidAt = paidAt;
    }

    public static Fine issue(UUID readerId, UUID borrowingId, UUID bookCopyId, FineLine line, Instant now) {
        Fine fine = new Fine(UUID.randomUUID(), readerId, borrowingId, bookCopyId, line.reason(), line.amount(),
                FineStatus.UNPAID, now, null);
        fine.events.add(new FineIssuedEvent(UUID.randomUUID(), fine.id, readerId, borrowingId, bookCopyId,
                line.reason().name(), line.amount(), now));
        return fine;
    }

    /** Dựng lại từ persistence, không phát sinh event. */
    public static Fine restore(UUID id, UUID readerId, UUID borrowingId, UUID bookCopyId, FineReason reason,
                               BigDecimal amount, FineStatus status, Instant createdAt, Instant paidAt) {
        return new Fine(id, readerId, borrowingId, bookCopyId, reason, amount, status, createdAt, paidAt);
    }

    /** Như {@link #restore} kèm tenant sở hữu (đọc xuyên tenant, ADR-0008). */
    public static Fine restore(UUID id, UUID readerId, UUID borrowingId, UUID bookCopyId, FineReason reason,
                               BigDecimal amount, FineStatus status, Instant createdAt, Instant paidAt, UUID tenantId) {
        Fine fine = new Fine(id, readerId, borrowingId, bookCopyId, reason, amount, status, createdAt, paidAt);
        fine.tenantId = tenantId;
        return fine;
    }

    /** UC09: thu khoản phạt. Thu hai lần là lỗi để không ghi nhận trùng. */
    public void pay(Instant now) {
        if (status == FineStatus.PAID) throw new InvalidFineException("Khoản phạt đã được thanh toán");
        status = FineStatus.PAID;
        paidAt = now;
        events.add(new FinePaidEvent(UUID.randomUUID(), id, readerId, amount, now));
    }

    public boolean isUnpaid() {
        return status == FineStatus.UNPAID;
    }

    public List<DomainEvent> pullEvents() {
        List<DomainEvent> copy = List.copyOf(events);
        events.clear();
        return copy;
    }

    public UUID id() { return id; }
    public UUID readerId() { return readerId; }
    public UUID borrowingId() { return borrowingId; }
    public UUID bookCopyId() { return bookCopyId; }
    public FineReason reason() { return reason; }
    public BigDecimal amount() { return amount; }
    public FineStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant paidAt() { return paidAt; }
    /** Null với khoản mới lập chưa qua persistence. */
    public UUID tenantId() { return tenantId; }
}

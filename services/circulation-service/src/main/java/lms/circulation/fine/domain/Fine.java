package lms.circulation.fine.domain;

import lms.common.kernel.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate Root: một khoản phí phạt của độc giả cho một bản sao trong một lượt mượn. UNPAID -> PAID (UC09), hoặc
 * UNPAID -> WAIVED/REDUCED khi đề nghị miễn/giảm được duyệt (REDUCED -> PAID khi thu phần còn lại).
 * amount là số tiền gốc, không bao giờ sửa; payableAmount là số phải thu sau miễn/giảm (bằng amount khi chưa điều chỉnh).
 */
public class Fine {

    private final UUID id;
    private final UUID readerId;
    private final UUID borrowingId;
    private final UUID bookCopyId;
    private final FineReason reason;
    private final BigDecimal amount;
    private BigDecimal payableAmount;
    private FineStatus status;
    private final Instant createdAt;
    private Instant paidAt;
    private final FineAssessment assessment;
    /** Tenant sở hữu, chỉ có khi dựng lại từ persistence (đọc); khoản mới lập chưa có (null). Không tham gia nghiệp vụ. */
    private UUID tenantId;
    private final List<DomainEvent> events = new ArrayList<>();

    private Fine(UUID id, UUID readerId, UUID borrowingId, UUID bookCopyId, FineReason reason, BigDecimal amount,
                 BigDecimal payableAmount, FineStatus status, Instant createdAt, Instant paidAt,
                 FineAssessment assessment) {
        if (id == null || readerId == null || borrowingId == null || bookCopyId == null) {
            throw new InvalidFineException("Khoản phạt thiếu định danh độc giả/lượt mượn/bản sao");
        }
        if (reason == null) throw new InvalidFineException("Thiếu lý do phạt");
        if (amount == null || amount.signum() <= 0) throw new InvalidFineException("Số tiền phạt phải > 0");
        if (status == null || createdAt == null) throw new InvalidFineException("Thiếu trạng thái hoặc thời điểm lập");
        if ((status == FineStatus.PAID) != (paidAt != null)) throw new InvalidFineException("Thời điểm thu không khớp trạng thái");
        BigDecimal payable = payableAmount == null ? amount : payableAmount;
        if (payable.signum() < 0 || payable.compareTo(amount) > 0) {
            throw new InvalidFineException("Số tiền phải thu phải từ 0 đến số tiền gốc");
        }
        if (status == FineStatus.WAIVED && payable.signum() != 0) throw new InvalidFineException("Khoản đã miễn phải thu 0");
        if (status == FineStatus.UNPAID && payable.compareTo(amount) != 0) {
            throw new InvalidFineException("Khoản chưa điều chỉnh phải thu đủ số tiền gốc");
        }
        if (status == FineStatus.REDUCED && (payable.signum() <= 0 || payable.compareTo(amount) >= 0)) {
            throw new InvalidFineException("Khoản đã giảm phải thu ít hơn số tiền gốc và lớn hơn 0");
        }
        this.id = id;
        this.readerId = readerId;
        this.borrowingId = borrowingId;
        this.bookCopyId = bookCopyId;
        this.reason = reason;
        this.amount = amount;
        this.payableAmount = payable;
        this.status = status;
        this.createdAt = createdAt;
        this.paidAt = paidAt;
        this.assessment = assessment;
    }

    public static Fine issue(UUID readerId, UUID borrowingId, UUID bookCopyId, FineLine line, Instant now) {
        Fine fine = new Fine(UUID.randomUUID(), readerId, borrowingId, bookCopyId, line.reason(), line.amount(),
                line.amount(), FineStatus.UNPAID, now, null, line.assessment());
        fine.events.add(new FineIssuedEvent(UUID.randomUUID(), fine.id, readerId, borrowingId, bookCopyId,
                line.reason().name(), line.amount(), now));
        return fine;
    }

    /** Dựng lại từ persistence, không phát sinh event. */
    public static Fine restore(UUID id, UUID readerId, UUID borrowingId, UUID bookCopyId, FineReason reason,
                               BigDecimal amount, FineStatus status, Instant createdAt, Instant paidAt) {
        return new Fine(id, readerId, borrowingId, bookCopyId, reason, amount, amount, status, createdAt, paidAt, null);
    }

    /** Như {@link #restore} kèm tenant sở hữu (đọc xuyên tenant, ADR-0008). */
    public static Fine restore(UUID id, UUID readerId, UUID borrowingId, UUID bookCopyId, FineReason reason,
                               BigDecimal amount, FineStatus status, Instant createdAt, Instant paidAt, UUID tenantId) {
        Fine fine = new Fine(id, readerId, borrowingId, bookCopyId, reason, amount, amount, status, createdAt, paidAt, null);
        fine.tenantId = tenantId;
        return fine;
    }

    /** Dựng lại đầy đủ (số phải thu sau miễn/giảm và snapshot cách tính). payableAmount null nghĩa là bằng amount. */
    public static Fine restore(UUID id, UUID readerId, UUID borrowingId, UUID bookCopyId, FineReason reason,
                               BigDecimal amount, BigDecimal payableAmount, FineStatus status, Instant createdAt,
                               Instant paidAt, FineAssessment assessment, UUID tenantId) {
        Fine fine = new Fine(id, readerId, borrowingId, bookCopyId, reason, amount, payableAmount, status, createdAt,
                paidAt, assessment);
        fine.tenantId = tenantId;
        return fine;
    }

    /** UC09: thu khoản phạt (số phải thu sau giảm nếu có). Thu hai lần hoặc thu khoản đã miễn là lỗi. */
    public void pay(Instant now) {
        if (status == FineStatus.PAID) throw new InvalidFineException("Khoản phạt đã được thanh toán");
        if (!status.collectible()) throw new InvalidFineException("Khoản phạt đã được miễn, không còn phải thu");
        status = FineStatus.PAID;
        paidAt = now;
        events.add(new FinePaidEvent(UUID.randomUUID(), id, readerId, payableAmount, now));
    }

    /**
     * Áp một đề nghị miễn/giảm đã được duyệt. Chỉ khoản UNPAID: khoản đã PAID chỉ xử lý bằng hoàn khoản, khoản đã
     * WAIVED/REDUCED không điều chỉnh lại. WAIVE: thành WAIVED, phải thu 0. REDUCE: thành REDUCED, newPayable phải lớn
     * hơn 0 và nhỏ hơn số tiền gốc. Số tiền gốc (amount) không đổi.
     */
    public void applyAdjustment(AdjustmentKind kind, BigDecimal newPayable, UUID requestedBy, UUID approvedBy, Instant now) {
        if (status != FineStatus.UNPAID) {
            throw FineRuleException.conflict("FINE_NOT_ADJUSTABLE",
                    "Chỉ miễn/giảm được khoản phạt chưa thu (hiện " + status + ")");
        }
        if (kind == AdjustmentKind.WAIVE) {
            payableAmount = BigDecimal.ZERO;
            status = FineStatus.WAIVED;
        } else {
            if (newPayable == null || newPayable.signum() <= 0 || newPayable.compareTo(amount) >= 0) {
                throw FineRuleException.invalid("INVALID_ADJUSTMENT_AMOUNT",
                        "Số tiền phải thu sau khi giảm phải lớn hơn 0 và nhỏ hơn số tiền gốc " + amount.toPlainString());
            }
            payableAmount = newPayable;
            status = FineStatus.REDUCED;
        }
        events.add(new FineWaivedEvent(UUID.randomUUID(), id, readerId, kind.name(), amount, payableAmount, requestedBy,
                approvedBy, now));
    }

    /**
     * Miễn toàn phần phần còn phải thu khi duyệt hoàn khoản vì tìm lại sách: nhận cả UNPAID lẫn REDUCED (đã giảm, chưa thu),
     * khác {@link #applyAdjustment} chỉ nhận UNPAID. Thành WAIVED, phải thu 0, số tiền gốc không đổi.
     */
    public void waiveRemainderForRefund(UUID requestedBy, UUID approvedBy, Instant now) {
        if (!status.collectible()) {
            throw FineRuleException.conflict("FINE_NOT_ADJUSTABLE",
                    "Chỉ miễn được khoản phạt còn phải thu (hiện " + status + ")");
        }
        payableAmount = BigDecimal.ZERO;
        status = FineStatus.WAIVED;
        events.add(new FineWaivedEvent(UUID.randomUUID(), id, readerId, AdjustmentKind.WAIVE.name(), amount, payableAmount,
                requestedBy, approvedBy, now));
    }

    /** Còn phải thu (UNPAID hoặc REDUCED): tính vào tổng nợ. */
    public boolean isUnpaid() {
        return status.collectible();
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
    /** Số tiền gốc, không đổi sau khi lập. */
    public BigDecimal amount() { return amount; }
    /** Số phải thu sau miễn/giảm (bằng amount khi chưa điều chỉnh, 0 khi đã miễn). */
    public BigDecimal payableAmount() { return payableAmount; }
    public FineStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant paidAt() { return paidAt; }
    /** Snapshot cách tính hỏng/mất; null với phí trễ và khoản cũ. */
    public FineAssessment assessment() { return assessment; }
    /** Null với khoản mới lập chưa qua persistence. */
    public UUID tenantId() { return tenantId; }
}

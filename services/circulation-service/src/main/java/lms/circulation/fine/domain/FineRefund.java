package lms.circulation.fine.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Đề nghị hoàn khoản đền bù khi tìm lại sách đã báo mất. Thủ thư ghi nhận "tìm lại" trong hạn foundWindowDays kể từ ngày
 * báo mất, kèm kiểm tra tình trạng (restoreStatus) và hình thức hoàn; người duyệt phải khác người đề nghị. Duyệt chỉ ghi
 * bút toán hoàn (không chuyển tiền thật). Số hoàn = số đã thu (sau giảm nếu có) trừ phí xử lý, tối thiểu 0.
 * Mỗi khoản mất chỉ hoàn một lần (unique một đề nghị PENDING/APPROVED cho mỗi khoản, kiểm thêm ở DB).
 */
public class FineRefund {

    private final UUID id;
    private final UUID fineId;
    private final UUID borrowingId;
    private final UUID bookCopyId;
    private final UUID readerId;
    private final LocalDate lostOn;
    private final LocalDate foundOn;
    private final RestoreStatus restoreStatus;
    private final String checkNote;
    private final RefundMethod method;
    private final String referenceCode;
    private final RefundMode mode;
    private final FineStatus fineStatusAtRequest;
    private final BigDecimal collectedAmount;
    private final BigDecimal processingFee;
    private final BigDecimal refundAmount;
    private ApprovalStatus status;
    private final UUID requestedBy;
    private final Instant requestedAt;
    private UUID decidedBy;
    private Instant decidedAt;
    private String decisionNote;
    private UUID tenantId;

    private FineRefund(UUID id, UUID fineId, UUID borrowingId, UUID bookCopyId, UUID readerId, LocalDate lostOn,
                       LocalDate foundOn, RestoreStatus restoreStatus, String checkNote, RefundMethod method,
                       String referenceCode, RefundMode mode, FineStatus fineStatusAtRequest, BigDecimal collectedAmount,
                       BigDecimal processingFee, BigDecimal refundAmount, ApprovalStatus status, UUID requestedBy,
                       Instant requestedAt, UUID decidedBy, Instant decidedAt, String decisionNote) {
        this.id = id;
        this.fineId = fineId;
        this.borrowingId = borrowingId;
        this.bookCopyId = bookCopyId;
        this.readerId = readerId;
        this.lostOn = lostOn;
        this.foundOn = foundOn;
        this.restoreStatus = restoreStatus;
        this.checkNote = checkNote;
        this.method = method;
        this.referenceCode = referenceCode;
        this.mode = mode;
        this.fineStatusAtRequest = fineStatusAtRequest;
        this.collectedAmount = collectedAmount;
        this.processingFee = processingFee;
        this.refundAmount = refundAmount;
        this.status = status;
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.decisionNote = decisionNote;
    }

    /**
     * Lập đề nghị từ khoản đền bù mất. Ném FineRuleException: LOST_FINE_REQUIRED nếu khoản không phải LOST,
     * REFUND_WINDOW_EXPIRED nếu quá hạn tìm lại.
     */
    public static FineRefund request(Fine lostFine, LocalDate lostOn, LocalDate foundOn, int foundWindowDays,
                                     RestoreStatus restoreStatus, String checkNote, RefundMethod method,
                                     String referenceCode, BigDecimal processingFee, UUID requestedBy, Instant now) {
        if (requestedBy == null) throw new InvalidFineException("Thiếu người đề nghị");
        if (lostFine.reason() != FineReason.LOST) {
            throw FineRuleException.invalid("LOST_FINE_REQUIRED", "Chỉ hoàn khoản cho khoản bồi thường mất sách");
        }
        if (restoreStatus == null) {
            throw FineRuleException.invalid("RESTORE_STATUS_REQUIRED", "Cần kiểm tra tình trạng: AVAILABLE hoặc DAMAGED");
        }
        if (method == null) {
            throw FineRuleException.invalid("REFUND_METHOD_REQUIRED", "Cần chọn hình thức hoàn: CASH hoặc BANK_TRANSFER");
        }
        if (foundOn.isAfter(lostOn.plusDays(foundWindowDays))) {
            throw FineRuleException.conflict("REFUND_WINDOW_EXPIRED",
                    "Đã quá hạn tìm lại sách (" + foundWindowDays + " ngày kể từ ngày báo mất " + lostOn + ")");
        }
        String note = clean(checkNote, 500, "INVALID_CHECK_NOTE", "Ghi chú kiểm tra tối đa 500 ký tự");
        String ref = clean(referenceCode, 100, "INVALID_REFERENCE_CODE", "Mã tham chiếu tối đa 100 ký tự");
        BigDecimal fee = processingFee == null ? BigDecimal.ZERO : processingFee;
        RefundMode mode;
        BigDecimal collected = BigDecimal.ZERO;
        BigDecimal refund = BigDecimal.ZERO;
        switch (lostFine.status()) {
            case PAID -> {
                mode = RefundMode.REFUND;
                collected = lostFine.payableAmount();
                refund = collected.subtract(fee).max(BigDecimal.ZERO);
            }
            case WAIVED -> mode = RefundMode.RESTORE_ONLY;
            default -> mode = RefundMode.WAIVE;
        }
        return new FineRefund(UUID.randomUUID(), lostFine.id(), lostFine.borrowingId(), lostFine.bookCopyId(),
                lostFine.readerId(), lostOn, foundOn, restoreStatus, note, method, ref, mode, lostFine.status(), collected,
                fee, refund, ApprovalStatus.PENDING, requestedBy, now, null, null, null);
    }

    public static FineRefund restore(UUID id, UUID fineId, UUID borrowingId, UUID bookCopyId, UUID readerId,
                                     LocalDate lostOn, LocalDate foundOn, RestoreStatus restoreStatus, String checkNote,
                                     RefundMethod method, String referenceCode, RefundMode mode,
                                     FineStatus fineStatusAtRequest, BigDecimal collectedAmount, BigDecimal processingFee,
                                     BigDecimal refundAmount, ApprovalStatus status, UUID requestedBy, Instant requestedAt,
                                     UUID decidedBy, Instant decidedAt, String decisionNote, UUID tenantId) {
        FineRefund r = new FineRefund(id, fineId, borrowingId, bookCopyId, readerId, lostOn, foundOn, restoreStatus,
                checkNote, method, referenceCode, mode, fineStatusAtRequest, collectedAmount, processingFee,
                refundAmount, status, requestedBy, requestedAt, decidedBy, decidedAt, decisionNote);
        r.tenantId = tenantId;
        return r;
    }

    /** Khoản phạt phải còn đúng trạng thái như lúc đề nghị thì mới duyệt (ví dụ vừa được thu hoặc vừa được miễn thì lập lại). */
    public void requireFineStateUnchanged(Fine fine) {
        if (fine.status() != fineStatusAtRequest) {
            throw FineRuleException.conflict("REFUND_FINE_STATE_CHANGED",
                    "Khoản phạt đã đổi trạng thái từ " + fineStatusAtRequest + " sang " + fine.status()
                            + " kể từ lúc đề nghị; hãy từ chối đề nghị này và lập lại");
        }
    }

    public void approve(UUID approverId, String note, Instant now) {
        decide(approverId, note, now, ApprovalStatus.APPROVED);
    }

    public void reject(UUID approverId, String note, Instant now) {
        decide(approverId, note, now, ApprovalStatus.REJECTED);
    }

    private void decide(UUID approverId, String note, Instant now, ApprovalStatus target) {
        if (approverId == null) throw new InvalidFineException("Thiếu người duyệt");
        if (status != ApprovalStatus.PENDING) {
            throw FineRuleException.conflict("REFUND_NOT_PENDING", "Đề nghị hoàn đã được xử lý (" + status + ")");
        }
        if (approverId.equals(requestedBy)) {
            throw FineRuleException.conflict("APPROVER_MUST_DIFFER", "Người duyệt phải khác người đề nghị");
        }
        status = target;
        decidedBy = approverId;
        decidedAt = now;
        decisionNote = clean(note, 500, "INVALID_DECISION_NOTE", "Ghi chú tối đa 500 ký tự");
    }

    private static String clean(String value, int max, String code, String message) {
        if (value == null) return null;
        String v = value.strip();
        if (v.isEmpty()) return null;
        if (v.length() > max) throw FineRuleException.invalid(code, message);
        return v;
    }

    public UUID id() { return id; }
    public UUID fineId() { return fineId; }
    public UUID borrowingId() { return borrowingId; }
    public UUID bookCopyId() { return bookCopyId; }
    public UUID readerId() { return readerId; }
    public LocalDate lostOn() { return lostOn; }
    public LocalDate foundOn() { return foundOn; }
    public RestoreStatus restoreStatus() { return restoreStatus; }
    public String checkNote() { return checkNote; }
    public RefundMethod method() { return method; }
    public String referenceCode() { return referenceCode; }
    public RefundMode mode() { return mode; }
    public FineStatus fineStatusAtRequest() { return fineStatusAtRequest; }
    /** Số đã thu của khoản mất lúc đề nghị (sau giảm nếu có); 0 khi chưa thu. */
    public BigDecimal collectedAmount() { return collectedAmount; }
    public BigDecimal processingFee() { return processingFee; }
    /** Số ghi bút toán hoàn khi duyệt (0 với WAIVE/RESTORE_ONLY). */
    public BigDecimal refundAmount() { return refundAmount; }
    public ApprovalStatus status() { return status; }
    public UUID requestedBy() { return requestedBy; }
    public Instant requestedAt() { return requestedAt; }
    public UUID decidedBy() { return decidedBy; }
    public Instant decidedAt() { return decidedAt; }
    public String decisionNote() { return decisionNote; }
    public UUID tenantId() { return tenantId; }
}

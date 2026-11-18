package lms.circulation.fine.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Đề nghị miễn toàn phần hoặc giảm một phần một khoản phạt chưa thu (UNPAID), kèm lý do bắt buộc. Người duyệt hoặc từ
 * chối phải KHÁC người đề nghị (APPROVER_MUST_DIFFER), kiểm ở đây chứ không dựa vào role. Chỉ chuyển PENDING sang
 * APPROVED/REJECTED một lần; lịch sử không sửa/xóa. fineAmount/fineReason/readerId là bản chụp lúc đề nghị để danh sách
 * không phải nối bảng fines. Việc áp kết quả lên khoản phạt do use case duyệt gọi {@link Fine#applyAdjustment}.
 */
public class FineAdjustmentRequest {

    public static final int MAX_REASON_LENGTH = 500;

    private final UUID id;
    private final UUID fineId;
    private final UUID readerId;
    private final FineReason fineReason;
    private final BigDecimal fineAmount;
    private final AdjustmentKind kind;
    private final BigDecimal payableAmount;
    private final String reason;
    private ApprovalStatus status;
    private final UUID requestedBy;
    private final Instant requestedAt;
    private UUID decidedBy;
    private Instant decidedAt;
    private String decisionNote;
    private UUID tenantId;

    private FineAdjustmentRequest(UUID id, UUID fineId, UUID readerId, FineReason fineReason, BigDecimal fineAmount,
                                  AdjustmentKind kind, BigDecimal payableAmount, String reason, ApprovalStatus status,
                                  UUID requestedBy, Instant requestedAt, UUID decidedBy, Instant decidedAt,
                                  String decisionNote) {
        this.id = id;
        this.fineId = fineId;
        this.readerId = readerId;
        this.fineReason = fineReason;
        this.fineAmount = fineAmount;
        this.kind = kind;
        this.payableAmount = payableAmount;
        this.reason = reason;
        this.status = status;
        this.requestedBy = requestedBy;
        this.requestedAt = requestedAt;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.decisionNote = decisionNote;
    }

    /**
     * Lập đề nghị cho một khoản phạt. Khoản phải UNPAID (đã PAID chỉ hoàn khoản, đã WAIVED/REDUCED không điều chỉnh
     * lại). WAIVE: số phải thu mới là 0, bỏ qua payableAmount truyền vào. REDUCE: payableAmount phải lớn hơn 0 và nhỏ
     * hơn số tiền gốc.
     */
    public static FineAdjustmentRequest request(Fine fine, AdjustmentKind kind, BigDecimal payableAmount, String reason,
                                                UUID requestedBy, Instant now) {
        if (requestedBy == null) throw new InvalidFineException("Thiếu người đề nghị");
        if (kind == null) throw FineRuleException.invalid("INVALID_ADJUSTMENT_KIND", "Cần chọn miễn toàn phần (WAIVE) hoặc giảm (REDUCE)");
        if (fine.status() != FineStatus.UNPAID) {
            throw FineRuleException.conflict("FINE_NOT_ADJUSTABLE",
                    "Chỉ đề nghị miễn/giảm khoản phạt chưa thu (hiện " + fine.status() + "); khoản đã thu thì dùng hoàn khoản");
        }
        String cleaned = reason == null ? "" : reason.strip();
        if (cleaned.isEmpty()) throw FineRuleException.invalid("ADJUSTMENT_REASON_REQUIRED", "Phải nêu lý do miễn/giảm");
        if (cleaned.length() > MAX_REASON_LENGTH) {
            throw FineRuleException.invalid("INVALID_ADJUSTMENT_REASON", "Lý do tối đa " + MAX_REASON_LENGTH + " ký tự");
        }
        BigDecimal payable;
        if (kind == AdjustmentKind.WAIVE) {
            payable = BigDecimal.ZERO;
        } else {
            if (payableAmount == null || payableAmount.signum() <= 0 || payableAmount.compareTo(fine.amount()) >= 0) {
                throw FineRuleException.invalid("INVALID_ADJUSTMENT_AMOUNT",
                        "Số tiền phải thu sau khi giảm phải lớn hơn 0 và nhỏ hơn số tiền gốc " + fine.amount().toPlainString());
            }
            payable = payableAmount;
        }
        return new FineAdjustmentRequest(UUID.randomUUID(), fine.id(), fine.readerId(), fine.reason(), fine.amount(), kind,
                payable, cleaned, ApprovalStatus.PENDING, requestedBy, now, null, null, null);
    }

    public static FineAdjustmentRequest restore(UUID id, UUID fineId, UUID readerId, FineReason fineReason,
                                                BigDecimal fineAmount, AdjustmentKind kind, BigDecimal payableAmount,
                                                String reason, ApprovalStatus status, UUID requestedBy,
                                                Instant requestedAt, UUID decidedBy, Instant decidedAt,
                                                String decisionNote, UUID tenantId) {
        FineAdjustmentRequest r = new FineAdjustmentRequest(id, fineId, readerId, fineReason, fineAmount, kind,
                payableAmount, reason, status, requestedBy, requestedAt, decidedBy, decidedAt, decisionNote);
        r.tenantId = tenantId;
        return r;
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
            throw FineRuleException.conflict("ADJUSTMENT_NOT_PENDING", "Đề nghị đã được xử lý (" + status + ")");
        }
        if (approverId.equals(requestedBy)) {
            throw FineRuleException.conflict("APPROVER_MUST_DIFFER", "Người duyệt phải khác người đề nghị");
        }
        String cleaned = note == null ? null : note.strip();
        if (cleaned != null && cleaned.length() > MAX_REASON_LENGTH) {
            throw FineRuleException.invalid("INVALID_DECISION_NOTE", "Ghi chú tối đa " + MAX_REASON_LENGTH + " ký tự");
        }
        status = target;
        decidedBy = approverId;
        decidedAt = now;
        decisionNote = cleaned == null || cleaned.isEmpty() ? null : cleaned;
    }

    public UUID id() { return id; }
    public UUID fineId() { return fineId; }
    public UUID readerId() { return readerId; }
    public FineReason fineReason() { return fineReason; }
    public BigDecimal fineAmount() { return fineAmount; }
    public AdjustmentKind kind() { return kind; }
    /** Số phải thu sau khi duyệt (0 với WAIVE). */
    public BigDecimal payableAmount() { return payableAmount; }
    public String reason() { return reason; }
    public ApprovalStatus status() { return status; }
    public UUID requestedBy() { return requestedBy; }
    public Instant requestedAt() { return requestedAt; }
    public UUID decidedBy() { return decidedBy; }
    public Instant decidedAt() { return decidedAt; }
    public String decisionNote() { return decisionNote; }
    public UUID tenantId() { return tenantId; }
}

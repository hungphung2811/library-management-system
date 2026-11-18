package lms.sales.shift.domain;

import java.time.Instant;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;

/**
 * Ca thu ngân (ADR-0030 D3). Gắn với một người thu ngân; mỗi người tối đa một ca OPEN (ràng buộc ở DB, kiểm trước ở use
 * case). Đóng ca chụp lại số liệu: tiền mặt dự kiến = tiền đầu ca + thu tiền mặt - hoàn tiền mặt trong ca, chênh lệch =
 * tiền đếm thực tế - dự kiến (dương là thừa, âm là thiếu). Chênh lệch vượt ngưỡng thì bắt buộc lý do và chờ quản lý duyệt.
 * Sau khi đóng ca bị khóa, chỉ quản lý mở lại (có lý do); mở lại xóa số liệu đóng cũ, lịch sử nằm ở {@link ShiftEvent}.
 */
public final class CashierShift {

    public static final int MAX_REASON = 500;
    public static final int MAX_LABEL = 100;

    private final UUID id;
    private final UUID tenantId;
    private final String shiftNo;
    private final UUID cashierUserId;
    private final String registerLabel;
    private ShiftStatus status;
    private final Instant openedAt;
    private final long openingFloat;
    private Instant closedAt;
    private UUID closedBy;
    private Long countedCash;
    private Long expectedCash;
    private Long variance;
    private String varianceReason;
    private VarianceStatus varianceStatus;
    private UUID approvedBy;
    private Instant approvedAt;
    private String approvalNote;
    private int reopenCount;
    private final UUID clientRequestId;
    private final long version;

    /** Toàn bộ trạng thái lưu trữ (dùng cho {@link #restore}). */
    public record Snapshot(UUID id, UUID tenantId, String shiftNo, UUID cashierUserId, String registerLabel,
                           ShiftStatus status, Instant openedAt, long openingFloat, Instant closedAt, UUID closedBy,
                           Long countedCash, Long expectedCash, Long variance, String varianceReason,
                           VarianceStatus varianceStatus, UUID approvedBy, Instant approvedAt, String approvalNote,
                           int reopenCount, UUID clientRequestId, long version) {
    }

    private CashierShift(Snapshot s) {
        this.id = s.id();
        this.tenantId = s.tenantId();
        this.shiftNo = s.shiftNo();
        this.cashierUserId = s.cashierUserId();
        this.registerLabel = s.registerLabel();
        this.status = s.status();
        this.openedAt = s.openedAt();
        this.openingFloat = s.openingFloat();
        this.closedAt = s.closedAt();
        this.closedBy = s.closedBy();
        this.countedCash = s.countedCash();
        this.expectedCash = s.expectedCash();
        this.variance = s.variance();
        this.varianceReason = s.varianceReason();
        this.varianceStatus = s.varianceStatus();
        this.approvedBy = s.approvedBy();
        this.approvedAt = s.approvedAt();
        this.approvalNote = s.approvalNote();
        this.reopenCount = s.reopenCount();
        this.clientRequestId = s.clientRequestId();
        this.version = s.version();
    }

    public static CashierShift restore(Snapshot snapshot) {
        return new CashierShift(snapshot);
    }

    /** Ca mới ở OPEN. Tiền đầu ca không âm; thiếu người thu ngân hoặc yêu cầu thì 400. */
    public static CashierShift open(UUID tenantId, String shiftNo, UUID cashierUserId, String registerLabel,
                                    long openingFloat, UUID clientRequestId, Instant now) {
        if (cashierUserId == null) {
            throw SalesException.invalidRequest("Thiếu người thu ngân");
        }
        if (clientRequestId == null) {
            throw SalesException.invalidRequest("Thiếu clientRequestId");
        }
        if (openingFloat < 0) {
            throw SalesException.invalidPrice("Tiền đầu ca không được âm");
        }
        String label = registerLabel == null || registerLabel.isBlank() ? null : registerLabel.trim();
        if (label != null && label.length() > MAX_LABEL) {
            throw SalesException.invalidRequest("Tên quầy tối đa " + MAX_LABEL + " ký tự");
        }
        return new CashierShift(new Snapshot(UUID.randomUUID(), tenantId, shiftNo, cashierUserId, label,
                ShiftStatus.OPEN, now, openingFloat, null, null, null, null, null, null, VarianceStatus.NONE, null,
                null, null, 0, clientRequestId, 0));
    }

    /** Tiền mặt dự kiến trong két: tiền đầu ca cộng thu tiền mặt trừ hoàn tiền mặt của ca. */
    public static long expectedCash(long openingFloat, long cashCollected, long cashRefunded) {
        return openingFloat + cashCollected - cashRefunded;
    }

    /** Chênh lệch = thực tế trừ dự kiến (dương là thừa, âm là thiếu). */
    public static long variance(long countedCash, long expectedCash) {
        return countedCash - expectedCash;
    }

    /** Chênh lệch có vượt ngưỡng (so sánh trị tuyệt đối, vượt hẳn) không. */
    public static boolean exceedsThreshold(long variance, long threshold) {
        return Math.abs(variance) > threshold;
    }

    /**
     * Đóng ca với số tiền đếm thực tế và số tiền mặt dự kiến do hệ thống tính. Chỉ từ OPEN (409 SHIFT_STATE_CONFLICT).
     * Chênh lệch vượt {@code threshold} thì lý do bắt buộc (400 SHIFT_VARIANCE_REASON_REQUIRED) và ca chờ duyệt.
     */
    public void close(long counted, long expected, String reason, long threshold, UUID by, Instant now) {
        if (status != ShiftStatus.OPEN) {
            throw new SalesException(ErrorCode.SHIFT_STATE_CONFLICT, "Ca không đang mở");
        }
        if (counted < 0) {
            throw SalesException.invalidPrice("Tiền đếm thực tế không được âm");
        }
        String clean = reason == null || reason.isBlank() ? null : reason.trim();
        if (clean != null && clean.length() > MAX_REASON) {
            throw SalesException.invalidRequest("Lý do tối đa " + MAX_REASON + " ký tự");
        }
        long diff = variance(counted, expected);
        boolean needsApproval = exceedsThreshold(diff, threshold);
        if (needsApproval && clean == null) {
            throw new SalesException(ErrorCode.SHIFT_VARIANCE_REASON_REQUIRED,
                    "Chênh lệch tiền mặt vượt ngưỡng, cần nhập lý do");
        }
        this.status = ShiftStatus.CLOSED;
        this.closedAt = now;
        this.closedBy = by;
        this.countedCash = counted;
        this.expectedCash = expected;
        this.variance = diff;
        this.varianceReason = clean;
        this.varianceStatus = needsApproval ? VarianceStatus.PENDING_APPROVAL : VarianceStatus.NONE;
        this.approvedBy = null;
        this.approvedAt = null;
        this.approvalNote = null;
    }

    /** Quản lý mở lại ca đã đóng (lý do bắt buộc). Xóa số liệu đóng; đóng lại sinh số liệu mới. */
    public void reopen(String reason) {
        if (status != ShiftStatus.CLOSED) {
            throw new SalesException(ErrorCode.SHIFT_STATE_CONFLICT, "Ca chưa đóng nên không mở lại được");
        }
        if (reason == null || reason.isBlank()) {
            throw new SalesException(ErrorCode.REOPEN_REASON_REQUIRED, "Mở lại ca cần có lý do");
        }
        if (reason.trim().length() > MAX_REASON) {
            throw SalesException.invalidRequest("Lý do tối đa " + MAX_REASON + " ký tự");
        }
        this.status = ShiftStatus.OPEN;
        this.closedAt = null;
        this.closedBy = null;
        this.countedCash = null;
        this.expectedCash = null;
        this.variance = null;
        this.varianceReason = null;
        this.varianceStatus = VarianceStatus.NONE;
        this.approvedBy = null;
        this.approvedAt = null;
        this.approvalNote = null;
        this.reopenCount++;
    }

    /** Quản lý duyệt chênh lệch: chỉ ca đã đóng đang chờ duyệt. Không có "từ chối" (từ chối là mở lại ca). */
    public void approveVariance(String note, UUID by, Instant now) {
        if (status != ShiftStatus.CLOSED || varianceStatus != VarianceStatus.PENDING_APPROVAL) {
            throw new SalesException(ErrorCode.SHIFT_STATE_CONFLICT, "Ca không có chênh lệch chờ duyệt");
        }
        String clean = note == null || note.isBlank() ? null : note.trim();
        if (clean != null && clean.length() > MAX_REASON) {
            throw SalesException.invalidRequest("Ghi chú tối đa " + MAX_REASON + " ký tự");
        }
        this.varianceStatus = VarianceStatus.APPROVED;
        this.approvedBy = by;
        this.approvedAt = now;
        this.approvalNote = clean;
    }

    public boolean isOpen() {
        return status == ShiftStatus.OPEN;
    }

    public boolean isOwnedBy(UUID userId) {
        return userId != null && userId.equals(cashierUserId);
    }

    public UUID id() { return id; }
    public UUID tenantId() { return tenantId; }
    public String shiftNo() { return shiftNo; }
    public UUID cashierUserId() { return cashierUserId; }
    public String registerLabel() { return registerLabel; }
    public ShiftStatus status() { return status; }
    public Instant openedAt() { return openedAt; }
    public long openingFloat() { return openingFloat; }
    public Instant closedAt() { return closedAt; }
    public UUID closedBy() { return closedBy; }
    public Long countedCash() { return countedCash; }
    public Long expectedCash() { return expectedCash; }
    public Long variance() { return variance; }
    public String varianceReason() { return varianceReason; }
    public VarianceStatus varianceStatus() { return varianceStatus; }
    public UUID approvedBy() { return approvedBy; }
    public Instant approvedAt() { return approvedAt; }
    public String approvalNote() { return approvalNote; }
    public int reopenCount() { return reopenCount; }
    public UUID clientRequestId() { return clientRequestId; }
    public long version() { return version; }
}

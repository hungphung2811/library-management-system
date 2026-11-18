package lms.sales.shift.application;

import java.time.Instant;
import java.util.UUID;
import lms.sales.shift.domain.CashierShift;
import lms.sales.shift.domain.ShiftStatus;
import lms.sales.shift.domain.VarianceStatus;

/**
 * Ca thu ngân cho API. {@code phase} gộp trạng thái để FE hiển thị: OPEN, REOPENED (đang mở sau khi quản lý mở lại),
 * PENDING_APPROVAL (đã đóng, chênh lệch chờ duyệt), CLOSED (đã đóng; chênh lệch đã duyệt hoặc không có).
 */
public record ShiftView(UUID id, String shiftNo, UUID cashierUserId, String registerLabel, ShiftStatus status,
                        String phase, Instant openedAt, long openingFloat, Instant closedAt, UUID closedBy,
                        Long countedCash, Long expectedCash, Long variance, String varianceReason,
                        VarianceStatus varianceStatus, UUID approvedBy, Instant approvedAt, String approvalNote,
                        int reopenCount, long version) {

    public static ShiftView from(CashierShift s) {
        return new ShiftView(s.id(), s.shiftNo(), s.cashierUserId(), s.registerLabel(), s.status(), phase(s),
                s.openedAt(), s.openingFloat(), s.closedAt(), s.closedBy(), s.countedCash(), s.expectedCash(),
                s.variance(), s.varianceReason(), s.varianceStatus(), s.approvedBy(), s.approvedAt(),
                s.approvalNote(), s.reopenCount(), s.version());
    }

    static String phase(CashierShift s) {
        if (s.status() == ShiftStatus.OPEN) {
            return s.reopenCount() > 0 ? "REOPENED" : "OPEN";
        }
        return s.varianceStatus() == VarianceStatus.PENDING_APPROVAL ? "PENDING_APPROVAL" : "CLOSED";
    }
}

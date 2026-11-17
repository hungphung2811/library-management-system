package lms.sales.ordering.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.Money;
import lms.sales.shared.domain.Rounding;
import lms.sales.shared.domain.SalesException;

/**
 * Tính phiếu trả (thuần): kiểm đơn đủ điều kiện, cửa sổ N ngày (hoặc ngoại lệ quá hạn), số lượng còn trả được và tiền
 * hoàn theo dòng. Tiền hoàn của một dòng = round(lineTotal x q / Q) HALF_UP; lần trả cuối của dòng nhận phần còn lại
 * (lineTotal - đã hoàn) nên tổng hoàn một dòng không bao giờ vượt lineTotal. Phí ship không được hoàn khi trả hàng.
 */
public final class ReturnPlanner {

    private ReturnPlanner() {
    }

    public record RequestLine(UUID orderLineId, int quantity, Disposition disposition) {
    }

    /** Đã trả/đã hoàn trước đó của một dòng đơn. */
    public record Prior(int quantity, long refunded) {
        public static final Prior NONE = new Prior(0, 0);
    }

    public record PlannedLine(UUID orderLineId, UUID titleId, int quantity, Disposition disposition,
                              Money refundAmount) {
    }

    public record Plan(boolean late, int daysOverdue, List<PlannedLine> lines) {
        public Money refundTotal() {
            long s = 0;
            for (PlannedLine l : lines) {
                s += l.refundAmount().amount();
            }
            return Money.of(s);
        }
    }

    /** Hạn trả = completedAt + N ngày. */
    public static Instant deadline(Instant completedAt, int windowDays) {
        return completedAt.plus(Duration.ofDays(windowDays));
    }

    /** Số ngày quá hạn (làm tròn lên, tối thiểu 1 khi đã quá hạn; 0 nếu còn hạn). */
    public static int daysOverdue(Instant deadline, Instant now) {
        if (!now.isAfter(deadline)) {
            return 0;
        }
        long seconds = Duration.between(deadline, now).getSeconds();
        return (int) Math.max(1, (seconds + 86_399) / 86_400);
    }

    public static Plan plan(SalesOrder order, Map<UUID, Prior> prior, List<RequestLine> request, int windowDays,
                            boolean lateException, String lateReason, Instant now) {
        if (lateException && (lateReason == null || lateReason.isBlank())) {
            throw new SalesException(ErrorCode.LATE_REASON_REQUIRED, "Bắt buộc nhập lý do trả quá hạn");
        }
        if (order.status() != OrderStatus.COMPLETED || order.completedAt() == null) {
            throw new SalesException(ErrorCode.ORDER_STATE_CONFLICT, "Chỉ đơn đã hoàn tất mới trả hàng được");
        }
        if (order.paymentStatus() == PaymentStatus.UNPAID) {
            throw new SalesException(ErrorCode.ORDER_NOT_PAID, "Đơn chưa thu tiền");
        }
        if (request == null || request.isEmpty()) {
            throw SalesException.invalidRequest("Phiếu trả cần ít nhất một dòng");
        }
        int overdue = daysOverdue(deadline(order.completedAt(), windowDays), now);
        if (overdue > 0 && !lateException) {
            throw new SalesException(ErrorCode.RETURN_WINDOW_EXPIRED, "Đã quá hạn trả hàng",
                    Map.of("windowDays", windowDays, "daysOverdue", overdue));
        }
        Set<UUID> seen = new HashSet<>();
        List<PlannedLine> out = new ArrayList<>();
        for (RequestLine r : request) {
            if (r.orderLineId() == null || r.disposition() == null) {
                throw SalesException.invalidRequest("Thiếu dòng đơn hoặc cách xử lý hàng trả");
            }
            if (r.quantity() <= 0) {
                throw SalesException.invalidQuantity("Số lượng trả phải lớn hơn 0");
            }
            if (!seen.add(r.orderLineId())) {
                throw SalesException.invalidRequest("Một dòng đơn chỉ xuất hiện một lần trong phiếu trả");
            }
            OrderLine line = order.lines().stream().filter(l -> l.id().equals(r.orderLineId())).findFirst()
                    .orElseThrow(() -> SalesException.invalidRequest("Dòng đơn không thuộc đơn hàng"));
            Prior p = prior.getOrDefault(line.id(), Prior.NONE);
            int remaining = line.quantity() - p.quantity();
            if (r.quantity() > remaining) {
                throw new SalesException(ErrorCode.RETURN_QUANTITY_EXCEEDED, "Số lượng trả vượt số đã mua",
                        Map.of("orderLineId", line.id().toString(), "remaining", remaining));
            }
            long total = line.lineTotal().amount();
            long left = total - p.refunded();
            long refund = r.quantity() == remaining ? left
                    : Math.min(left, Rounding.mulDivHalfUp(total, r.quantity(), line.quantity()));
            out.add(new PlannedLine(line.id(), line.titleId(), r.quantity(), r.disposition(),
                    Money.of(Math.max(0, refund))));
        }
        return new Plan(lateException, overdue, out);
    }
}

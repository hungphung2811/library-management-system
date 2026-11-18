package lms.sales.ordering.application;

import lms.sales.settings.application.OrderAbuseLimits;

/**
 * Chấm mức rủi ro của một lần đặt đơn online từ tín hiệu có trong dữ liệu đơn của khách (tỷ lệ hủy, số đơn gần đây,
 * số đơn chờ, số lượng). Thuần tính toán, ngưỡng do cấu hình cấp ({@link Thresholds}), không hardcode.
 *
 * <p>Hành động theo mức: LOW cho qua; MEDIUM cho qua nhưng ghi mức vào audit để rà soát; HIGH siết trần đơn chờ còn một
 * nửa; CRITICAL chặn. Chưa dùng tín hiệu email/điện thoại/thiết bị (hệ thống chưa có xác minh và định danh thiết bị).
 */
public class AntiAbuseService {

    public enum RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

    /** Ngưỡng theo phần trăm tỷ lệ hủy (chỉ xét khi khách có ít nhất {@code minOrders} đơn trong 30 ngày). */
    public record Thresholds(int minOrders, int mediumCancelPercent, int highCancelPercent, int criticalCancelPercent) {
    }

    /** Tín hiệu của khách tại thời điểm đặt. */
    public record Signals(long orders30d, long cancelled30d, long orders24h, long pending, long requestedQuantity) {
    }

    public record Assessment(RiskLevel level, int effectiveMaxPending) {
        public boolean blocked() {
            return level == RiskLevel.CRITICAL;
        }
    }

    private final Thresholds t;

    public AntiAbuseService(Thresholds thresholds) {
        this.t = thresholds;
    }

    public Assessment assess(Signals s, OrderAbuseLimits limits) {
        double cancelPercent = s.orders30d() >= t.minOrders() ? 100.0 * s.cancelled30d() / s.orders30d() : 0;
        RiskLevel level = RiskLevel.LOW;
        if (cancelPercent >= t.criticalCancelPercent()) {
            level = RiskLevel.CRITICAL;
        } else if (cancelPercent >= t.highCancelPercent() || s.orders24h() * 4 >= limits.maxOrdersPerDay() * 3L) {
            level = RiskLevel.HIGH;
        } else if (cancelPercent >= t.mediumCancelPercent() || s.requestedQuantity() * 2 > limits.maxTotalQuantity()) {
            level = RiskLevel.MEDIUM;
        }
        int maxPending = level == RiskLevel.HIGH ? Math.max(1, limits.maxPendingOrders() / 2)
                : limits.maxPendingOrders();
        return new Assessment(level, maxPending);
    }
}

package lms.sales.settings.domain;

import java.time.Instant;
import java.util.UUID;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;

/**
 * Chính sách chống lạm dụng đặt hàng online theo tenant (hạn giữ hàng, trần số đơn chờ, số đơn trong ngày, số lượng,
 * tốc độ đặt). Tạo lười: tenant chưa lưu thì dùng {@link Values} mặc định do cấu hình ứng dụng cấp (không hardcode ở
 * chỗ dùng). {@code reservationMinutes = 0} nghĩa là đơn chờ xác nhận không tự hết hạn.
 */
public final class OrderAbusePolicy {

    /** Bộ giá trị (mặc định hoặc đã lưu). */
    public record Values(int reservationMinutes, int maxPendingOrders, int maxOrdersPerDay, int maxQuantityPerLine,
                         int maxTotalQuantity, int ordersPerMinutePerUser, int ordersPerMinuteTenant) {
        public Values {
            require(reservationMinutes >= 0 && reservationMinutes <= 10_080,
                    "Hạn giữ hàng phải trong 0 đến 10080 phút (0 = không hết hạn)");
            require(maxPendingOrders >= 1 && maxPendingOrders <= 1_000, "Số đơn chờ tối đa phải trong 1 đến 1000");
            require(maxOrdersPerDay >= 1 && maxOrdersPerDay <= 10_000,
                    "Số đơn mỗi ngày tối đa phải trong 1 đến 10000");
            require(maxQuantityPerLine >= 1 && maxQuantityPerLine <= 10_000,
                    "Số lượng mỗi dòng tối đa phải trong 1 đến 10000");
            require(maxTotalQuantity >= 1 && maxTotalQuantity <= 100_000,
                    "Tổng số lượng mỗi đơn tối đa phải trong 1 đến 100000");
            require(ordersPerMinutePerUser >= 1 && ordersPerMinutePerUser <= 600,
                    "Số đơn mỗi phút của một khách phải trong 1 đến 600");
            require(ordersPerMinuteTenant >= 1 && ordersPerMinuteTenant <= 100_000,
                    "Số đơn mỗi phút của cả tenant phải trong 1 đến 100000");
        }
    }

    private final UUID tenantId;
    private final Values values;
    private final long version;
    private final Instant updatedAt;
    private final UUID updatedBy;

    private OrderAbusePolicy(UUID tenantId, Values values, long version, Instant updatedAt, UUID updatedBy) {
        this.tenantId = tenantId;
        this.values = values;
        this.version = version;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    /** Chưa lưu: version 0 và giá trị mặc định. */
    public static OrderAbusePolicy defaults(UUID tenantId, Values defaults) {
        return new OrderAbusePolicy(tenantId, defaults, 0, null, null);
    }

    public static OrderAbusePolicy restore(UUID tenantId, Values values, long version, Instant updatedAt,
                                           UUID updatedBy) {
        return new OrderAbusePolicy(tenantId, values, version, updatedAt, updatedBy);
    }

    /** Bản mới (cùng version; adapter tăng version khi lưu). */
    public OrderAbusePolicy update(Values newValues, Instant now, UUID actor) {
        return new OrderAbusePolicy(tenantId, newValues, version, now, actor);
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new SalesException(ErrorCode.INVALID_SETTINGS, message);
        }
    }

    public UUID tenantId() { return tenantId; }
    public Values values() { return values; }
    public long version() { return version; }
    public Instant updatedAt() { return updatedAt; }
    public UUID updatedBy() { return updatedBy; }
}

package lms.sales.shared.infrastructure.demo;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import lms.demo.plan.DemoPlan;

/**
 * Quy đổi độ lệch ngày/giờ của {@link DemoPlan} thành mốc thời gian cho dữ liệu seed bán hàng (ADR-0018). Dùng chung
 * cho bước seed tồn kho (sổ cái SALE/RETURN) và bước seed đơn hàng để hai bên ra cùng một mốc.
 * <p>
 * {@code minuteOfDay} của kế hoạch là giờ VN; mọi mốc không được vượt quá thời điểm hiện tại (đơn hôm nay vào buổi
 * sáng sớm không được có giờ ở tương lai) nên bị kẹp về {@code clock.instant()}.
 */
public final class DemoTimes {

    /** Cùng múi giờ nghiệp vụ với {@code DemoSeedRunner} và SalesStatsQueryAdapter. */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    /** Thuế suất demo 10%, giá chưa gồm VAT: khớp công thức vat = (tạm tính - giảm) / 10 của kế hoạch. */
    public static final int VAT_RATE_BPS = 1000;
    public static final boolean PRICES_INCLUDE_VAT = false;
    /** Phí ship cố định của đơn online trong kế hoạch. */
    public static final long FIXED_SHIPPING_FEE = 30_000L;
    public static final int RETURN_WINDOW_DAYS = 7;
    public static final int WALK_IN_RETENTION_MONTHS = 12;

    private DemoTimes() {
    }

    /** {@code today - daysAgo} lúc {@code minuteOfDay} (giờ VN), chưa kẹp. */
    public static Instant at(LocalDate today, int daysAgo, int minuteOfDay) {
        return today.minusDays(daysAgo).atStartOfDay(BUSINESS_ZONE).plusMinutes(minuteOfDay).toInstant();
    }

    /** Kẹp không vượt quá hiện tại. */
    public static Instant clamp(Instant time, Clock clock) {
        Instant now = clock.instant();
        return time.isAfter(now) ? now : time;
    }

    public static Instant plusClamped(Instant time, Duration delta, Clock clock) {
        return clamp(time.plus(delta), clock);
    }

    public static Instant orderCreatedAt(DemoPlan.OrderPlan order, DemoPlan plan, Clock clock) {
        return clamp(at(plan.today(), order.createdDaysAgo(), order.minuteOfDay()), clock);
    }

    /**
     * Thời điểm tạo phiếu trả: cùng ngày với đơn thì sau đơn 3 giờ (đơn tạo muộn nhất 19:59 nên không sang ngày),
     * khác ngày thì 10:00 giờ VN của ngày trả.
     */
    public static Instant returnCreatedAt(DemoPlan.SalesReturnPlan ret, DemoPlan plan, Clock clock) {
        DemoPlan.OrderPlan order = plan.orders().get(ret.orderIndex());
        if (ret.createdDaysAgo() == order.createdDaysAgo()) {
            return plusClamped(orderCreatedAt(order, plan, clock), Duration.ofHours(3), clock);
        }
        return clamp(at(plan.today(), ret.createdDaysAgo(), 600), clock);
    }
}

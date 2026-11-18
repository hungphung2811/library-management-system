package lms.sales.reporting.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lms.sales.reporting.application.SalesStatsQueryPort.SoldTitle;
import lms.sales.reporting.domain.ReportRange;

/**
 * Top đầu sách bán chạy cho storefront (khám phá tác giả nổi bật): dùng lại truy vấn topSold của dashboard (đơn đã
 * thanh toán trong khoảng, trừ hàng trả) nhưng chỉ lộ titleId và quantity. Giá trị days/limit lạ được chặn về
 * khoảng hợp lệ, không ném lỗi.
 */
public class StorefrontTopSoldUseCase {

    public static final int DEFAULT_DAYS = 90;
    public static final int MAX_DAYS = 365;
    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 100;

    public record TopSold(UUID titleId, long quantity) {
    }

    private final SalesStatsQueryPort query;
    private final Clock clock;

    public StorefrontTopSoldUseCase(SalesStatsQueryPort query, Clock clock) {
        this.query = query;
        this.clock = clock;
    }

    /** {@code days} null = 90, chặn 1..365; {@code limit} null = 100, chặn 1..100. Khoảng gồm cả hôm nay (giờ VN). */
    public List<TopSold> execute(UUID tenantId, Integer days, Integer limit) {
        int d = days == null ? DEFAULT_DAYS : Math.max(1, Math.min(days, MAX_DAYS));
        int n = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        LocalDate today = LocalDate.now(clock.withZone(ReportRange.ZONE));
        ReportRange range = new ReportRange(today.minusDays(d - 1L), today);
        return query.topSold(tenantId, range, n).stream().map(StorefrontTopSoldUseCase::toView).toList();
    }

    private static TopSold toView(SoldTitle s) {
        return new TopSold(s.titleId(), s.quantity());
    }
}

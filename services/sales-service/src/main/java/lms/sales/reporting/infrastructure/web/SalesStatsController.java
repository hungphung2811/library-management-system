package lms.sales.reporting.infrastructure.web;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lms.sales.reporting.application.SalesStatsUseCases;
import lms.sales.reporting.application.SalesStatsUseCases.LowStockItem;
import lms.sales.reporting.application.SalesStatsUseCases.Summary;
import lms.sales.reporting.domain.RevenueTrend;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thống kê bán hàng cho dashboard (ADR-0018). Khóa: summary, revenueTrend, topSoldTitles (đổi tên method là đổi khóa
 * quyền, đã seed ở migration identity V25).
 */
@RestController
@RequestMapping("/internal/sales/reporting")
@RequiresModule(TenantModule.SALES)
public class SalesStatsController {

    public record SummaryQuery(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
    }

    public record RangeQuery(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    }

    public record TopQuery(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, Integer limit) {
    }

    public record SummaryResponse(LocalDate asOf, String timezone, long ordersToday, long grossToday,
                                  long refundsToday, long revenueToday, long pendingOnlineOrders,
                                  long lowStockCount, List<LowStockItemResponse> lowStockItems) {
        static SummaryResponse from(Summary s) {
            return new SummaryResponse(s.asOf(), "Asia/Ho_Chi_Minh", s.ordersToday(), s.grossToday(),
                    s.refundsToday(), s.revenueToday(), s.pendingOnlineOrders(), s.lowStockCount(),
                    s.lowStockItems().stream().map(LowStockItemResponse::from).toList());
        }
    }

    public record LowStockItemResponse(UUID titleId, String titleName, long available, int threshold) {
        static LowStockItemResponse from(LowStockItem i) {
            return new LowStockItemResponse(i.titleId(), i.titleName(), i.available(), i.threshold());
        }
    }

    public record RevenueTrendResponse(LocalDate from, LocalDate to, TotalsResponse totals,
                                       List<PointResponse> points) {
        static RevenueTrendResponse from(RevenueTrend t) {
            return new RevenueTrendResponse(t.range().from(), t.range().to(),
                    new TotalsResponse(t.totals().gross(), t.totals().refunds(), t.totals().net(),
                            t.totals().orders()),
                    t.points().stream().map(p -> new PointResponse(p.date(), p.gross(), p.refunds(), p.net(),
                            p.orders())).toList());
        }
    }

    public record TotalsResponse(long gross, long refunds, long net, long orders) {
    }

    public record PointResponse(LocalDate date, long gross, long refunds, long net, long orders) {
    }

    public record TopSoldResponse(List<TopSoldItem> items) {
    }

    public record TopSoldItem(UUID titleId, String titleName, long quantity, long revenue) {
    }

    private final SalesStatsUseCases stats;

    public SalesStatsController(SalesStatsUseCases stats) {
        this.stats = stats;
    }

    @GetMapping("/summary")
    public SummaryResponse summary(SummaryQuery q) {
        return SummaryResponse.from(stats.summary(RequestSupport.tenant(), q.asOf()));
    }

    @GetMapping("/revenue-trend")
    public RevenueTrendResponse revenueTrend(RangeQuery q) {
        return RevenueTrendResponse.from(stats.revenueTrend(RequestSupport.tenant(), q.from(), q.to()));
    }

    @GetMapping("/top-titles")
    public TopSoldResponse topSoldTitles(TopQuery q) {
        return new TopSoldResponse(stats.topSold(RequestSupport.tenant(), q.from(), q.to(), q.limit()).stream()
                .map(t -> new TopSoldItem(t.titleId(), t.titleName(), t.quantity(), t.revenue())).toList());
    }
}

package lms.api.dashboard.web;

import com.fasterxml.jackson.databind.JsonNode;
import lms.api.dashboard.client.DashboardStatsClient;
import lms.api.dashboard.dto.ChartQuery;
import lms.api.dashboard.dto.RangeQuery;
import lms.api.dashboard.dto.SummaryView;
import lms.api.dashboard.dto.TopQuery;
import lms.api.dashboard.service.DashboardAggregator;
import lms.api.dashboard.service.TitleNameEnricher;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard thống kê (ADR-0018). lms-api không phân quyền: mỗi nguồn tự kiểm khóa {@code Controller/method} và module
 * trên token được chuyển tiếp. Biểu đồ và top là endpoint một nguồn, lỗi của service đi theo ApiExceptionHandler.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardAggregator aggregator;
    private final DashboardStatsClient stats;
    private final TitleNameEnricher titles;

    public DashboardController(DashboardAggregator aggregator, DashboardStatsClient stats, TitleNameEnricher titles) {
        this.aggregator = aggregator;
        this.stats = stats;
        this.titles = titles;
    }

    /** {@code X-Tenant-Id}: chỉ platform admin dùng (ADR-0016); người khác bị service bỏ qua. */
    @GetMapping("/summary")
    public SummaryView summary(@AuthenticationPrincipal Jwt jwt,
                               @RequestHeader(name = "X-Tenant-Id", required = false) String tenantId) {
        return aggregator.summary(jwt, tenantId);
    }

    @GetMapping("/charts/borrowings")
    public JsonNode borrowings(ChartQuery query) {
        return stats.borrowingTrend(query);
    }

    @GetMapping("/charts/revenue")
    public JsonNode revenue(RangeQuery query) {
        return stats.revenueTrend(query);
    }

    /** Circulation chỉ giữ titleId; tên do catalog, thiếu khóa lookup hoặc catalog lỗi thì titleName = null. */
    @GetMapping("/top-titles/borrowed")
    public JsonNode topBorrowed(TopQuery query) {
        JsonNode result = stats.topBorrowed(query);
        if (result != null) {
            titles.fill(result.get("items"));
        }
        return result;
    }

    @GetMapping("/top-titles/sold")
    public JsonNode topSold(TopQuery query) {
        return stats.topSold(query);
    }
}

package lms.api.dashboard.client.http;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import lms.api.dashboard.client.DashboardStatsClient;
import lms.api.dashboard.dto.ChartQuery;
import lms.api.dashboard.dto.RangeQuery;
import lms.api.dashboard.dto.TopQuery;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/** RestClient có TokenRelayInterceptor (token và X-Tenant-Id của người gọi), timeout ngắn do cấu hình dựng. */
public class HttpDashboardStatsClient implements DashboardStatsClient {

    static final String CATALOG_SUMMARY = "/internal/reporting/catalog/summary";
    static final String LENDING = "/internal/reporting/lending";
    static final String SALES = "/internal/sales/reporting";

    private final RestClient catalog;
    private final RestClient circulation;
    private final RestClient sales;

    public HttpDashboardStatsClient(RestClient catalog, RestClient circulation, RestClient sales) {
        this.catalog = catalog;
        this.circulation = circulation;
        this.sales = sales;
    }

    @Override
    public JsonNode catalogSummary() {
        return get(catalog, CATALOG_SUMMARY, params());
    }

    @Override
    public JsonNode lendingSummary(LocalDate asOf) {
        return get(circulation, LENDING + "/summary", params("asOf", asOf.toString()));
    }

    @Override
    public JsonNode salesSummary(LocalDate asOf) {
        return get(sales, SALES + "/summary", params("asOf", asOf.toString()));
    }

    @Override
    public JsonNode borrowingTrend(ChartQuery q) {
        return get(circulation, LENDING + "/borrowing-trend",
                params("from", q.from(), "to", q.to(), "granularity", q.granularity()));
    }

    @Override
    public JsonNode revenueTrend(RangeQuery q) {
        return get(sales, SALES + "/revenue-trend", params("from", q.from(), "to", q.to()));
    }

    @Override
    public JsonNode topBorrowed(TopQuery q) {
        return get(circulation, LENDING + "/top-titles", params("from", q.from(), "to", q.to(), "limit", q.limit()));
    }

    @Override
    public JsonNode topSold(TopQuery q) {
        return get(sales, SALES + "/top-titles", params("from", q.from(), "to", q.to(), "limit", q.limit()));
    }

    private static Map<String, String> params(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static JsonNode get(RestClient client, String path, Map<String, String> params) {
        return client.get().uri(b -> {
            b.path(path);
            params.forEach((k, v) -> {
                if (v != null && !v.isBlank()) {
                    b.queryParam(k, v);
                }
            });
            return b.build();
        }).accept(MediaType.APPLICATION_JSON).retrieve().body(JsonNode.class);
    }
}

package lms.api.dashboard.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import lms.api.dashboard.dto.ChartQuery;
import lms.api.dashboard.dto.RangeQuery;
import lms.api.dashboard.dto.TopQuery;

/**
 * Cổng đọc số liệu thống kê từ catalog, circulation, sales (ADR-0018 mục 2.5). Thân là JSON thô của service.
 * Mã 4xx/5xx ném {@code RestClientResponseException}, không kết nối được ném {@code ResourceAccessException}.
 */
public interface DashboardStatsClient {

    JsonNode catalogSummary();

    /** Trả {@code {lending:{...}, fines:{...}}}. */
    JsonNode lendingSummary(LocalDate asOf);

    JsonNode salesSummary(LocalDate asOf);

    JsonNode borrowingTrend(ChartQuery query);

    JsonNode revenueTrend(RangeQuery query);

    JsonNode topBorrowed(TopQuery query);

    JsonNode topSold(TopQuery query);
}

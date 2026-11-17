package lms.api.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Response GET /api/dashboard/summary. Các phần {@code catalog}, {@code lending}, {@code fines}, {@code sales} là JSON
 * do service sở hữu trả (BFF không diễn giải thêm); phần thiếu là {@code null}.
 */
public record SummaryView(OffsetDateTime generatedAt, String timezone,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String tenantId,
                          List<String> modules, JsonNode catalog, JsonNode lending, JsonNode fines, JsonNode sales,
                          List<String> disabledModules, List<String> forbiddenSections,
                          List<String> unavailableSections) {
}

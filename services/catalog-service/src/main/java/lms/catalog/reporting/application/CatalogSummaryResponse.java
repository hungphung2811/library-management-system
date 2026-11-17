package lms.catalog.reporting.application;

import java.util.Map;
import lms.catalog.reporting.domain.CatalogSummary;

/** Phản hồi GET /internal/reporting/catalog/summary. */
public record CatalogSummaryResponse(long titles, Copies copies) {

    public record Copies(long total, Map<String, Long> byStatus) {
    }

    public static CatalogSummaryResponse from(CatalogSummary summary) {
        return new CatalogSummaryResponse(summary.titles(),
                new Copies(summary.copies().total(), summary.copies().byStatus()));
    }
}

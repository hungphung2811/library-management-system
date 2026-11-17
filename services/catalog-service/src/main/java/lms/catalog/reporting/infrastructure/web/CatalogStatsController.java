package lms.catalog.reporting.infrastructure.web;

import lms.catalog.reporting.application.CatalogSummaryResponse;
import lms.catalog.reporting.application.GetCatalogSummaryUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thống kê danh mục cho dashboard (ADR-0018). Module lõi, không {@code @RequiresModule}.
 * Khóa quyền {@code CatalogStatsController/summary}: không đổi tên controller/method.
 */
@RestController
@RequestMapping("/internal/reporting/catalog")
public class CatalogStatsController {

    private final GetCatalogSummaryUseCase getSummary;

    public CatalogStatsController(GetCatalogSummaryUseCase getSummary) {
        this.getSummary = getSummary;
    }

    @GetMapping("/summary")
    public CatalogSummaryResponse summary() {
        return getSummary.execute();
    }
}

package lms.catalog.reporting.application;

import lms.catalog.reporting.domain.CatalogSummary;
import lms.catalog.reporting.domain.CopyCounts;
import org.springframework.transaction.annotation.Transactional;

/** Tổng quan danh mục của tenant hiện tại; hai truy vấn cùng một transaction đọc để số liệu nhất quán. */
public class GetCatalogSummaryUseCase {

    private final CatalogStatsQuery query;

    public GetCatalogSummaryUseCase(CatalogStatsQuery query) {
        this.query = query;
    }

    @Transactional(readOnly = true)
    public CatalogSummaryResponse execute() {
        long titles = query.countTitles();
        CopyCounts copies = new CopyCounts(query.countCopiesByStatus());
        return CatalogSummaryResponse.from(new CatalogSummary(titles, copies));
    }
}

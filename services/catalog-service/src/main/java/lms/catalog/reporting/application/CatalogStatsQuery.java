package lms.catalog.reporting.application;

import java.util.Map;

/** Cổng đọc thống kê danh mục của tenant hiện tại (chỉ đọc). */
public interface CatalogStatsQuery {

    /** Số đầu sách của tenant hiện tại. */
    long countTitles();

    /** Số bản sao theo tên trạng thái của tenant hiện tại; trạng thái không có bản sao thì vắng trong map. */
    Map<String, Long> countCopiesByStatus();
}

package lms.catalog.reporting.domain;

/** Số liệu tổng quan danh mục của một tenant: tổng đầu sách và số bản sao theo trạng thái. */
public record CatalogSummary(long titles, CopyCounts copies) {
}

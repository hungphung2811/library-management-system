package lms.api.reports.client;

import lms.api.reports.dto.OrdersReportQuery;

/**
 * Cổng lấy báo cáo CSV từ service (ADR-0018 mục 2.3). Mã 2xx thì gọi {@code sink} với luồng byte (không đệm cả file);
 * 4xx/5xx ném {@code RestClientResponseException} trước khi sink được gọi, nên lỗi trả về JSON bình thường.
 */
public interface ReportClient {

    void overdueBorrowings(ReportSink sink);

    void stock(String lowStockOnly, ReportSink sink);

    void orders(OrdersReportQuery query, ReportSink sink);
}

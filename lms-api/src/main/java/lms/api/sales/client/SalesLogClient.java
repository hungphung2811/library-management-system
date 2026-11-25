package lms.api.sales.client;

import lms.api.sales.dto.SalesLogDtos.LogPage;
import lms.api.sales.dto.SalesLogDtos.LogQuery;

/**
 * Cổng sang /internal/sales/logs/** của sales-service (ADR-0029). Khóa quyền, module và cách ly tenant kiểm ở
 * sales-service; client này chỉ chuyển tiếp, lỗi 4xx chuyển nguyên.
 */
public interface SalesLogClient {

    LogPage integrationLogs(LogQuery query);

    LogPage systemLogs(LogQuery query);
}

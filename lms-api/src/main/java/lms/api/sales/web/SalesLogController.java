package lms.api.sales.web;

import lms.api.sales.client.SalesLogClient;
import lms.api.sales.dto.SalesLogDtos.LogPage;
import lms.api.sales.dto.SalesLogDtos.LogQuery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Log vận hành của sales (ADR-0029): chuyển nguyên sang /internal/sales/logs/integration và /system (kiểm khóa
 * OperationalLogController/integration|system, module SALES và cách ly tenant ở sales-service; lms-api chỉ xác thực).
 */
@RestController
@RequestMapping("/api/sales/logs")
public class SalesLogController {

    private final SalesLogClient logs;

    public SalesLogController(SalesLogClient logs) {
        this.logs = logs;
    }

    @GetMapping("/integration")
    public LogPage integration(LogQuery query) {
        return logs.integrationLogs(query);
    }

    @GetMapping("/system")
    public LogPage system(LogQuery query) {
        return logs.systemLogs(query);
    }
}

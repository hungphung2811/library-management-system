package lms.sales.reporting.infrastructure.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;
import lms.common.csv.CsvWriter;
import lms.sales.reporting.application.OrdersCsvReportUseCase;
import lms.sales.reporting.application.ReportPlan;
import lms.sales.reporting.application.StockCsvReportUseCase;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Báo cáo CSV (ADR-0018 mục 2.3). Ghi ĐỒNG BỘ vào output stream của response ngay trên luồng request (không dùng
 * StreamingResponseBody: luồng khác mất TenantContext). Lỗi tham số/đếm xảy ra trước byte đầu nên trả JSON bình thường.
 * Khóa: stockCsv, ordersCsv.
 */
@RestController
@RequestMapping("/internal/sales/reporting")
@RequiresModule(TenantModule.SALES)
public class SalesReportController {

    private static final Logger log = LoggerFactory.getLogger(SalesReportController.class);

    public record StockReportQuery(Boolean lowStockOnly) {
    }

    public record OrdersReportQuery(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, String channel,
                                    String status) {
    }

    private final StockCsvReportUseCase stockReport;
    private final OrdersCsvReportUseCase ordersReport;

    public SalesReportController(StockCsvReportUseCase stockReport, OrdersCsvReportUseCase ordersReport) {
        this.stockReport = stockReport;
        this.ordersReport = ordersReport;
    }

    @GetMapping("/stock.csv")
    public void stockCsv(StockReportQuery q, @AuthenticationPrincipal Jwt jwt, HttpServletResponse response)
            throws IOException {
        UUID tenant = RequestSupport.tenant();
        boolean lowOnly = Boolean.TRUE.equals(q.lowStockOnly());
        ReportPlan plan = stockReport.prepare(tenant, lowOnly);
        CsvWriter csv = start(response, plan);
        int rows = stockReport.write(tenant, lowOnly, plan, csv);
        csv.flush();
        logExport(plan, jwt, tenant, rows);
    }

    @GetMapping("/orders.csv")
    public void ordersCsv(OrdersReportQuery q, @AuthenticationPrincipal Jwt jwt, HttpServletResponse response)
            throws IOException {
        UUID tenant = RequestSupport.tenant();
        var prepared = ordersReport.prepare(tenant, q.from(), q.to(), q.channel(), q.status());
        CsvWriter csv = start(response, prepared.plan());
        int rows = ordersReport.write(tenant, prepared, csv);
        csv.flush();
        logExport(prepared.plan(), jwt, tenant, rows);
    }

    /** Đặt header rồi mở writer (ghi BOM). Không đóng writer: container đóng response. */
    private static CsvWriter start(HttpServletResponse response, ReportPlan plan) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + plan.filename() + "\"");
        response.setHeader("Cache-Control", "no-store");
        if (plan.truncated()) {
            response.setHeader("X-Report-Truncated", "true");
        }
        return CsvWriter.toStream(response.getOutputStream());
    }

    /** Không log giá trị ô hay bộ lọc, chỉ định danh và số dòng. */
    private static void logExport(ReportPlan plan, Jwt jwt, UUID tenant, int rows) {
        log.info("report-export report={} actorId={} tenantId={} rows={} truncated={}", plan.report(),
                RequestSupport.user(jwt), tenant, rows, plan.truncated());
    }
}

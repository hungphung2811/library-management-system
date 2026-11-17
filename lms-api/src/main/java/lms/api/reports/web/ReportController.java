package lms.api.reports.web;

import jakarta.servlet.http.HttpServletResponse;
import lms.api.reports.client.ReportClient;
import lms.api.reports.client.ReportSink;
import lms.api.reports.dto.OrdersReportQuery;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Báo cáo CSV (ADR-0018 mục 2.3): service sở hữu dữ liệu sinh CSV, lms-api chỉ chuyển luồng byte. Đồng bộ trên luồng
 * request nên TokenRelayInterceptor vẫn có token và X-Tenant-Id. Không có khóa quyền riêng ở đây: service kiểm.
 * Lỗi trước byte đầu là JSON lỗi bình thường; lỗi sau khi đã ghi chỉ cắt được kết nối.
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    static final String DEFAULT_CONTENT_TYPE = "text/csv; charset=UTF-8";
    static final String TRUNCATED_HEADER = "X-Report-Truncated";

    private final ReportClient reports;

    public ReportController(ReportClient reports) {
        this.reports = reports;
    }

    @GetMapping("/overdue-borrowings")
    public void overdueBorrowings(HttpServletResponse response) {
        reports.overdueBorrowings(sink(response));
    }

    /** Một tham số query nên không cần record. */
    @GetMapping("/stock")
    public void stock(@RequestParam(required = false) String lowStockOnly, HttpServletResponse response) {
        reports.stock(lowStockOnly, sink(response));
    }

    @GetMapping("/orders")
    public void orders(OrdersReportQuery query, HttpServletResponse response) {
        reports.orders(query, sink(response));
    }

    private static ReportSink sink(HttpServletResponse response) {
        return (headers, body) -> {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(headers.contentType() != null ? headers.contentType() : DEFAULT_CONTENT_TYPE);
            if (headers.contentDisposition() != null) {
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION, headers.contentDisposition());
            }
            if (headers.truncated() != null) {
                response.setHeader(TRUNCATED_HEADER, headers.truncated());
            }
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            body.transferTo(response.getOutputStream());
        };
    }
}

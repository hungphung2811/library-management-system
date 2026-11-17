package lms.circulation.reporting.infrastructure.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lms.circulation.reporting.application.ExportOverdueBorrowingsCsvUseCase;
import lms.security.CurrentUser;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
import lms.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Báo cáo CSV mượn quá hạn (ADR-0018 mục 2.3). Khóa quyền động {@code LendingReportController/overdueBorrowingsCsv}
 * (xuất dữ liệu hàng loạt, tách khỏi khóa xem dashboard). Ghi đồng bộ ngay trên luồng request (không
 * StreamingResponseBody: TenantContext là ThreadLocal, ghi ở luồng khác thì RLS mất tenant).
 */
@RestController
@RequiresModule(TenantModule.LENDING)
@RequestMapping("/internal/reporting/lending")
public class LendingReportController {

    private static final Logger log = LoggerFactory.getLogger(LendingReportController.class);

    private final ExportOverdueBorrowingsCsvUseCase export;

    public LendingReportController(ExportOverdueBorrowingsCsvUseCase export) {
        this.export = export;
    }

    @GetMapping("/overdue-borrowings.csv")
    public void overdueBorrowingsCsv(@AuthenticationPrincipal Jwt jwt, HttpServletResponse response) throws IOException {
        // Đếm trước: lỗi (quyền, tenant, DB) xảy ra trước byte đầu nên trả JSON lỗi bình thường.
        ExportOverdueBorrowingsCsvUseCase.Export prepared = export.prepare();

        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + prepared.fileName() + "\"");
        response.setHeader("Cache-Control", "no-store");
        if (prepared.truncated()) {
            response.setHeader("X-Report-Truncated", "true");
        }

        int rows = prepared.writeTo(response.getOutputStream());
        response.getOutputStream().flush();

        // Không ghi giá trị ô vào log.
        log.info("report-export report={} actorId={} tenantId={} rows={} truncated={}",
                ExportOverdueBorrowingsCsvUseCase.REPORT_NAME,
                jwt == null ? null : CurrentUser.from(jwt).userId(),
                TenantContext.current().orElse(null), rows, prepared.truncated());
    }
}

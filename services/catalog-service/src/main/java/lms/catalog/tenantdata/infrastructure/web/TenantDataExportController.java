package lms.catalog.tenantdata.infrastructure.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lms.security.permission.TenantDataAccess;
import lms.tenant.TenantContext;
import lms.tenant.export.TenantDataExporter;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Xuất dữ liệu catalog của một tenant (ADR-0031 Phụ lục A). Chỉ token dịch vụ {@code svc = tenant-data} của identity vào
 * được ({@code @TenantDataAccess}); tenant lấy từ claim {@code tenantId} (đã bind vào {@code TenantContext}), không nhận
 * tham số tenant. Phản hồi là ZIP ghi theo luồng. Khóa quyền không đăng ký (nhánh tenant-data bỏ qua kiểm khóa).
 */
@RestController
@RequestMapping("/internal/tenant-data")
public class TenantDataExportController {

    private final TenantDataExporter exporter;

    public TenantDataExportController(TenantDataExporter exporter) {
        this.exporter = exporter;
    }

    @TenantDataAccess
    @GetMapping(value = "/export", produces = "application/zip")
    public void export(HttpServletResponse response) throws IOException {
        var tenantId = TenantContext.require();
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"catalog-tenant-data.zip\"");
        exporter.export(tenantId, response.getOutputStream());
    }
}

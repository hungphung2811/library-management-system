package lms.circulation.tenantdata.infrastructure.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantDataAccess;
import lms.security.permission.TenantModule;
import lms.tenant.TenantContext;
import lms.tenant.export.TenantDataExporter;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Xuất dữ liệu circulation của một tenant (ADR-0031 Phụ lục A). Chỉ token dịch vụ {@code svc = tenant-data} của identity
 * vào được ({@code @TenantDataAccess}); tenant lấy từ claim {@code tenantId} (đã bind vào {@code TenantContext}), không
 * nhận tham số tenant. Phản hồi là ZIP ghi theo luồng. Khóa quyền không đăng ký (nhánh tenant-data bỏ qua kiểm khóa).
 *
 * <p>{@code @RequiresModule(LENDING)} chỉ để thỏa luật ArchUnit ADR-0009 (mọi controller circulation thuộc LENDING); token
 * tenant-data bỏ qua kiểm module nên tenant đã thu hồi module vẫn xuất được dữ liệu.
 */
@RequiresModule(TenantModule.LENDING)
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
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"circulation-tenant-data.zip\"");
        exporter.export(tenantId, response.getOutputStream());
    }
}

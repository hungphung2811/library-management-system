package lms.sales.tenantdata.infrastructure.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import lms.sales.shared.infrastructure.web.RequestSupport;
import lms.sales.tenantdata.application.ExportSalesTenantDataUseCase;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantDataAccess;
import lms.security.permission.TenantModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exporter dữ liệu sales của một tenant cho điều phối ở identity (ADR-0031 D1, Phụ lục A). Chỉ token dịch vụ
 * {@code svc = tenant-data} vào được (mọi token khác 403, do PermissionInterceptor); tenant lấy duy nhất từ claim
 * {@code tenantId} của token, không nhận từ query/header. {@code @RequiresModule(SALES)} chỉ để thỏa luật kiến trúc:
 * token tenant-data bỏ qua kiểm module (tenant đã thu hồi module vẫn xuất được). Ghi thân ZIP thẳng vào luồng phản hồi
 * trên chính luồng request (giữ nguyên {@code TenantContext} cho RLS), không giữ gói trong bộ nhớ.
 */
@RestController
@RequestMapping("/internal/tenant-data")
@RequiresModule(TenantModule.SALES)
public class TenantDataExportController {

    private static final Logger log = LoggerFactory.getLogger(TenantDataExportController.class);

    private final ExportSalesTenantDataUseCase useCase;

    public TenantDataExportController(ExportSalesTenantDataUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping("/export")
    @TenantDataAccess
    public void export(HttpServletResponse response) throws IOException {
        UUID tenantId = RequestSupport.tenant();
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/zip");
        response.setHeader("Cache-Control", "no-store");
        long started = System.nanoTime();
        ExportSalesTenantDataUseCase.Summary summary = useCase.export(tenantId, response.getOutputStream());
        // Không log dữ liệu: chỉ tenant, số bảng, số dòng, thời gian
        log.info("tenant-data export sales tenant={} tables={} rows={} ms={}", tenantId, summary.tables(),
                summary.rows(), (System.nanoTime() - started) / 1_000_000);
    }
}

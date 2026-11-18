package lms.api.tenantdata.web;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import lms.api.tenantdata.client.TenantDataClient;
import lms.api.tenantdata.client.TenantDataClient.Download;
import lms.api.tenantdata.dto.TenantDataDtos.ExportRequest;
import lms.api.tenantdata.dto.TenantDataDtos.ExportView;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Xuất trọn dữ liệu tenant (ADR-0031 F2): quản trị tenant yêu cầu, xem tiến độ và tải gói ZIP. Chuyển sang
 * {@code /internal/tenant-data/exports/**} của identity-service (khóa động {@code TenantDataExportController/request|list|get|
 * download}, tenant lấy từ token, id của tenant khác trả 404). Tải xuống phát theo luồng, không nạp cả gói vào bộ nhớ.
 * Platform admin: {@code /api/platform/tenants/{tenantId}/data-exports/**} (POST/GET chuyển nguyên bởi
 * IdentityGatewayController; riêng {@code .../download} ở đây để phát theo luồng).
 */
@RestController
public class TenantDataExportController {

    private final TenantDataClient exports;

    public TenantDataExportController(TenantDataClient exports) {
        this.exports = exports;
    }

    @PostMapping("/api/tenant-data/exports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ExportView request(@Valid @RequestBody(required = false) ExportRequest request) {
        return exports.request(request == null ? new ExportRequest(null) : request);
    }

    @GetMapping("/api/tenant-data/exports")
    public List<ExportView> list() {
        return exports.list();
    }

    @GetMapping("/api/tenant-data/exports/{id}")
    public ExportView get(@PathVariable UUID id) {
        return exports.get(id);
    }

    @GetMapping("/api/tenant-data/exports/{id}/download")
    public void download(@PathVariable UUID id, HttpServletResponse response) {
        exports.download(id, d -> copy(d, response));
    }

    @GetMapping("/api/platform/tenants/{tenantId}/data-exports/{id}/download")
    public void downloadForTenant(@PathVariable UUID tenantId, @PathVariable UUID id, HttpServletResponse response) {
        exports.downloadForTenant(tenantId, id, d -> copy(d, response));
    }

    private static void copy(Download d, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(d.contentType() == null ? "application/zip" : d.contentType());
        if (d.contentLength() != null) {
            response.setContentLengthLong(d.contentLength());
        }
        if (d.contentDisposition() != null) {
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, d.contentDisposition());
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        try (InputStream in = d.body()) {
            in.transferTo(response.getOutputStream());
        }
    }
}

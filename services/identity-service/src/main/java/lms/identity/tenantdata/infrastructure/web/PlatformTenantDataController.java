package lms.identity.tenantdata.infrastructure.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lms.identity.tenantdata.application.DownloadTenantDataExportUseCase;
import lms.identity.tenantdata.application.DownloadTenantDataExportUseCase.Download;
import lms.identity.tenantdata.application.ExportViews.ExportView;
import lms.identity.tenantdata.application.QueryTenantDataExportUseCase;
import lms.identity.tenantdata.application.RequestTenantDataExportUseCase;
import lms.identity.tenantdata.infrastructure.web.TenantDataExportController.ExportRequest;
import lms.security.CurrentUser;
import lms.security.permission.PlatformAdminOnly;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform admin xuất thay dữ liệu của một tenant (ADR-0031 mục 3.2, ADR-0008/0016): {@code @PlatformAdminOnly}, tenant lấy
 * từ đường dẫn, không dùng khóa Controller/method. Bỏ qua giới hạn 1 lần/24 giờ nhưng vẫn một yêu cầu đang chạy mỗi tenant;
 * mọi yêu cầu/tải ghi audit và log platform-audit.
 */
@RestController
@RequestMapping("/api/platform/tenants/{tenantId}/data-exports")
@PlatformAdminOnly
public class PlatformTenantDataController {

    private final RequestTenantDataExportUseCase request;
    private final QueryTenantDataExportUseCase query;
    private final DownloadTenantDataExportUseCase download;

    public PlatformTenantDataController(RequestTenantDataExportUseCase request, QueryTenantDataExportUseCase query,
                                        DownloadTenantDataExportUseCase download) {
        this.request = request;
        this.query = query;
        this.download = download;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ExportView export(@PathVariable UUID tenantId, @Valid @RequestBody(required = false) ExportRequest body,
                             @AuthenticationPrincipal Jwt jwt) {
        return request.request(tenantId, CurrentUser.from(jwt).userId(), true, body == null ? null : body.reason());
    }

    @GetMapping
    public List<ExportView> list(@PathVariable UUID tenantId) {
        return query.list(tenantId);
    }

    @GetMapping("/{id}")
    public ExportView get(@PathVariable UUID tenantId, @PathVariable UUID id) {
        return query.get(tenantId, id);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID tenantId, @PathVariable UUID id,
                                                        @AuthenticationPrincipal Jwt jwt) {
        Download d = download.open(tenantId, id, CurrentUser.from(jwt).userId(), true);
        return TenantDataExportController.streamed(d);
    }
}

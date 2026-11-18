package lms.identity.tenantdata.infrastructure.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lms.identity.tenantdata.application.DownloadTenantDataExportUseCase;
import lms.identity.tenantdata.application.DownloadTenantDataExportUseCase.Download;
import lms.identity.tenantdata.application.ExportViews.ExportView;
import lms.identity.tenantdata.application.QueryTenantDataExportUseCase;
import lms.identity.tenantdata.application.RequestTenantDataExportUseCase;
import lms.security.CurrentUser;
import lms.security.permission.AllowedWhenReadOnly;
import lms.tenant.TenantContext;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * Quản trị tenant yêu cầu/xem/tải gói xuất dữ liệu CỦA MÌNH (ADR-0031). KHÔNG có annotation quyền: khóa động
 * {@code TenantDataExportController/request|list|get|download} do PermissionInterceptor tự đăng ký và kiểm (role
 * {@code is_admin} đi qua sẵn; không gán cho mẫu vai trò nào, seed ở identity V40). Đổi tên lớp/method làm đổi khóa.
 * Tenant luôn lấy từ token (TenantContext), không bao giờ từ client: id của tenant khác trả 404 như id không tồn tại.
 * Tải xuống phát theo luồng (giải mã từng khung), không nạp cả gói vào bộ nhớ.
 */
@RestController
@RequestMapping("/internal/tenant-data/exports")
public class TenantDataExportController {

    private final RequestTenantDataExportUseCase request;
    private final QueryTenantDataExportUseCase query;
    private final DownloadTenantDataExportUseCase download;

    public TenantDataExportController(RequestTenantDataExportUseCase request, QueryTenantDataExportUseCase query,
                                      DownloadTenantDataExportUseCase download) {
        this.request = request;
        this.query = query;
        this.download = download;
    }

    /** Lý do tùy chọn (hiển thị trong lịch sử/audit). */
    public record ExportRequest(@jakarta.validation.constraints.Size(max = 500) String reason) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @AllowedWhenReadOnly
    public ExportView request(@Valid @RequestBody(required = false) ExportRequest body,
                              @AuthenticationPrincipal Jwt jwt) {
        return request.request(TenantContext.require(), CurrentUser.from(jwt).userId(), false,
                body == null ? null : body.reason());
    }

    @GetMapping
    public List<ExportView> list() {
        return query.list(TenantContext.require());
    }

    @GetMapping("/{id}")
    public ExportView get(@PathVariable UUID id) {
        return query.get(TenantContext.require(), id);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        Download d = download.open(TenantContext.require(), id, CurrentUser.from(jwt).userId(), false);
        return streamed(d);
    }

    static ResponseEntity<InputStreamResource> streamed(Download d) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(d.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(d.filename()).build().toString())
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(d.stream()));
    }
}

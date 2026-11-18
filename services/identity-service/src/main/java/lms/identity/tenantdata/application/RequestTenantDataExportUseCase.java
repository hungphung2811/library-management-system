package lms.identity.tenantdata.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.identity.tenantdata.application.ExportViews.ExportView;
import lms.identity.tenantdata.domain.TenantDataExport;
import lms.identity.tenantdata.domain.TenantDataExportException;
import lms.identity.tenantdata.domain.TenantDataExportException.Reason;
import lms.identity.tenantdata.domain.TenantDataExportRepository;

/**
 * Tạo yêu cầu xuất (PENDING). Quy tắc (ADR-0031 D3): tính năng phải được cấu hình (có khóa mã hóa), mỗi tenant tối đa MỘT
 * yêu cầu đang chạy (409 EXPORT_IN_PROGRESS), tối đa 1 yêu cầu thành công mỗi 24 giờ (429 EXPORT_RATE_LIMITED; platform
 * admin xuất thay được bỏ qua giới hạn này). Tenant do người gọi truyền vào: controller của tenant lấy từ TenantContext
 * (không từ client), controller platform lấy từ đường dẫn.
 */
public class RequestTenantDataExportUseCase {

    private final TenantDataExportRepository exports;
    private final ExportStoragePort storage;
    private final TenantLookupPort tenants;
    private final ExportPolicy policy;
    private final ExportAuditor auditor;
    private final Clock clock;

    public RequestTenantDataExportUseCase(TenantDataExportRepository exports, ExportStoragePort storage,
                                          TenantLookupPort tenants, ExportPolicy policy,
                                          lms.identity.tenant.application.PublishPlatformEventPort events,
                                          Clock clock) {
        this.exports = exports;
        this.storage = storage;
        this.tenants = tenants;
        this.policy = policy;
        this.auditor = new ExportAuditor(events);
        this.clock = clock;
    }

    public ExportView request(UUID tenantId, UUID actor, boolean byPlatform, String reason) {
        if (!storage.configured()) {
            throw new TenantDataExportException(Reason.EXPORT_NOT_CONFIGURED, "Tính năng xuất dữ liệu chưa được cấu hình");
        }
        if (byPlatform && tenants.codeOf(tenantId).isEmpty()) {
            throw new TenantDataExportException(Reason.TENANT_NOT_FOUND, "Không tìm thấy tenant");
        }
        Instant now = clock.instant();
        if (exports.hasInProgress(tenantId)) {
            throw new TenantDataExportException(Reason.EXPORT_IN_PROGRESS, "Đã có một yêu cầu xuất đang xử lý");
        }
        if (!byPlatform && exports.hasSuccessSince(tenantId, now.minus(policy.rateLimit()))) {
            throw new TenantDataExportException(Reason.EXPORT_RATE_LIMITED,
                    "Mỗi tenant chỉ được xuất thành công một lần trong " + policy.rateLimit().toHours() + " giờ");
        }
        TenantDataExport export = TenantDataExport.request(tenantId, actor, byPlatform, reason, now);
        exports.insert(export);
        auditor.record(ExportAuditor.REQUESTED, actor, byPlatform, tenantId, export.id(),
                byPlatform ? "platform" : "tenant");
        return ExportViews.of(export, List.of());
    }
}

package lms.identity.tenantdata.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.identity.tenantdata.application.ExportViews.ExportView;
import lms.identity.tenantdata.domain.ExportPart;
import lms.identity.tenantdata.domain.ExportPartRepository;
import lms.identity.tenantdata.domain.TenantDataExport;
import lms.identity.tenantdata.domain.TenantDataExportException;
import lms.identity.tenantdata.domain.TenantDataExportException.Reason;
import lms.identity.tenantdata.domain.TenantDataExportRepository;

/**
 * Danh sách và chi tiết yêu cầu xuất của MỘT tenant. Chống IDOR: tra theo (tenantId, id); id của tenant khác và id không
 * tồn tại cùng trả 404 EXPORT_NOT_FOUND với cùng thân. Danh sách nạp tiến độ nguồn theo lô (không N+1).
 */
public class QueryTenantDataExportUseCase {

    static final int LIST_LIMIT = 20;

    private final TenantDataExportRepository exports;
    private final ExportPartRepository parts;

    public QueryTenantDataExportUseCase(TenantDataExportRepository exports, ExportPartRepository parts) {
        this.exports = exports;
        this.parts = parts;
    }

    public List<ExportView> list(UUID tenantId) {
        List<TenantDataExport> rows = exports.listByTenant(tenantId, LIST_LIMIT);
        Map<UUID, List<ExportPart>> byExport = parts.findByExports(rows.stream().map(TenantDataExport::id).toList());
        return rows.stream().map(e -> ExportViews.of(e, byExport.getOrDefault(e.id(), List.of()))).toList();
    }

    public ExportView get(UUID tenantId, UUID id) {
        TenantDataExport e = exports.find(tenantId, id).orElseThrow(QueryTenantDataExportUseCase::notFound);
        return ExportViews.of(e, parts.findByExport(id));
    }

    static TenantDataExportException notFound() {
        return new TenantDataExportException(Reason.EXPORT_NOT_FOUND, "Không tìm thấy gói xuất");
    }
}

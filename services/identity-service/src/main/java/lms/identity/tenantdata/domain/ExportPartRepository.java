package lms.identity.tenantdata.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Cổng lưu tiến độ từng nguồn (tenant_data_export_parts, khóa (export_id, service)). */
public interface ExportPartRepository {

    void save(ExportPart part);

    List<ExportPart> findByExport(UUID exportId);

    /** Nạp theo lô cho danh sách (tránh N+1). */
    Map<UUID, List<ExportPart>> findByExports(Collection<UUID> exportIds);

    void deleteByExport(UUID exportId);
}

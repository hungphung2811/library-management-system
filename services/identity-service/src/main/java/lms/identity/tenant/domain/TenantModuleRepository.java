package lms.identity.tenant.domain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bảng toàn cục tenant_modules (identity_db, không RLS). */
public interface TenantModuleRepository {

    TenantModules findByTenant(UUID tenantId);

    /** Ghi (thêm hoặc cập nhật) một dòng module của tenant. */
    void save(UUID tenantId, TenantModuleEntry entry);

    /** Module đang bật của nhiều tenant, theo thứ tự enum; tenant không có dòng bật nào thì không có khóa trong map. */
    Map<UUID, List<ModuleCode>> enabledOf(Collection<UUID> tenantIds);
}

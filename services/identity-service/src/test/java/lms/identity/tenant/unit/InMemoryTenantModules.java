package lms.identity.tenant.unit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.identity.tenant.domain.ModuleCode;
import lms.identity.tenant.domain.TenantModuleEntry;
import lms.identity.tenant.domain.TenantModuleRepository;
import lms.identity.tenant.domain.TenantModules;

/** Kho tenant_modules trong bộ nhớ cho test đơn vị. */
class InMemoryTenantModules implements TenantModuleRepository {

    final Map<UUID, Map<ModuleCode, TenantModuleEntry>> rows = new HashMap<>();

    /** Tenant có sẵn các module này (đang bật). */
    void give(UUID tenantId, ModuleCode... modules) {
        for (ModuleCode m : modules) save(tenantId, new TenantModuleEntry(m, true, null, null));
    }

    @Override
    public TenantModules findByTenant(UUID tenantId) {
        return TenantModules.restore(new ArrayList<>(rows.getOrDefault(tenantId, Map.of()).values()));
    }

    @Override
    public void save(UUID tenantId, TenantModuleEntry entry) {
        rows.computeIfAbsent(tenantId, k -> new HashMap<>()).put(entry.module(), entry);
    }

    @Override
    public Map<UUID, List<ModuleCode>> enabledOf(Collection<UUID> tenantIds) {
        Map<UUID, List<ModuleCode>> result = new HashMap<>();
        for (UUID id : tenantIds) {
            List<ModuleCode> enabled = findByTenant(id).enabled();
            if (!enabled.isEmpty()) result.put(id, enabled);
        }
        return result;
    }
}

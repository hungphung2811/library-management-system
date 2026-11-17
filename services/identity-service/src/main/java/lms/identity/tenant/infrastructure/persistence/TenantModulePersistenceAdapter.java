package lms.identity.tenant.infrastructure.persistence;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.identity.tenant.domain.ModuleCode;
import lms.identity.tenant.domain.TenantModuleEntry;
import lms.identity.tenant.domain.TenantModuleRepository;
import lms.identity.tenant.domain.TenantModules;
import org.springframework.stereotype.Repository;

@Repository
class TenantModulePersistenceAdapter implements TenantModuleRepository {

    private final SpringDataTenantModuleRepository jpa;

    TenantModulePersistenceAdapter(SpringDataTenantModuleRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public TenantModules findByTenant(UUID tenantId) {
        return TenantModules.restore(jpa.findByTenant(tenantId).stream().map(TenantModulePersistenceAdapter::toDomain)
                .toList());
    }

    @Override
    public void save(UUID tenantId, TenantModuleEntry entry) {
        jpa.saveAndFlush(new TenantModuleJpaEntity(tenantId, entry.module().name(), entry.enabled(),
                entry.updatedAt(), entry.updatedBy()));
    }

    @Override
    public Map<UUID, List<ModuleCode>> enabledOf(Collection<UUID> tenantIds) {
        Map<UUID, List<ModuleCode>> result = new HashMap<>();
        if (tenantIds.isEmpty()) return result;
        for (TenantModuleJpaEntity e : jpa.findEnabledByTenants(tenantIds)) {
            result.computeIfAbsent(e.getTenantId(), k -> new java.util.ArrayList<>()).add(ModuleCode.valueOf(e.getModule()));
        }
        result.values().forEach(l -> l.sort(Comparator.naturalOrder()));
        return result;
    }

    private static TenantModuleEntry toDomain(TenantModuleJpaEntity e) {
        return new TenantModuleEntry(ModuleCode.valueOf(e.getModule()), e.isEnabled(), e.getUpdatedAt(),
                e.getUpdatedBy());
    }
}

package lms.identity.tenant.application;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.identity.tenant.domain.ModuleCode;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantModuleRepository;
import lms.identity.tenant.domain.TenantRepository;
import lms.identity.tenant.domain.TenantStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * Số liệu tổng hợp tenant cho tổng quan nền tảng (ADR-0018 mục 2.1). Chỉ đọc bảng toàn cục tenants/tenant_modules,
 * không cần TenantContext, không trả dữ liệu nghiệp vụ của tenant.
 */
public class TenantOverviewQuery {

    /** {@code activeByModule}: số tenant ACTIVE đang bật từng module (đủ mọi module, thiếu thì 0). */
    public record TenantOverview(long total, long active, long suspended, Map<String, Long> activeByModule) {
    }

    private final TenantRepository tenants;
    private final TenantModuleRepository modules;

    public TenantOverviewQuery(TenantRepository tenants, TenantModuleRepository modules) {
        this.tenants = tenants;
        this.modules = modules;
    }

    @Transactional(readOnly = true)
    public TenantOverview execute() {
        List<Tenant> all = tenants.findAll();
        Map<TenantStatus, Long> byStatus = new EnumMap<>(TenantStatus.class);
        for (Tenant t : all) byStatus.merge(t.status(), 1L, Long::sum);
        List<UUID> activeIds = all.stream().filter(Tenant::isActive).map(Tenant::id).toList();
        Map<String, Long> perModule = new LinkedHashMap<>();
        for (ModuleCode m : ModuleCode.values()) perModule.put(m.name(), 0L);
        if (!activeIds.isEmpty()) {
            modules.enabledOf(activeIds).values()
                    .forEach(enabled -> enabled.forEach(m -> perModule.merge(m.name(), 1L, Long::sum)));
        }
        return new TenantOverview(all.size(), byStatus.getOrDefault(TenantStatus.ACTIVE, 0L),
                byStatus.getOrDefault(TenantStatus.SUSPENDED, 0L), perModule);
    }
}

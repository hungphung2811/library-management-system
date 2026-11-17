package lms.identity.tenant.domain;

import java.time.Instant;
import java.util.UUID;

/** Trạng thái một module của tenant; updatedAt/updatedBy null khi module chưa từng được bật. */
public record TenantModuleEntry(ModuleCode module, boolean enabled, Instant updatedAt, UUID updatedBy) {

    public static TenantModuleEntry neverEnabled(ModuleCode module) {
        return new TenantModuleEntry(module, false, null, null);
    }
}

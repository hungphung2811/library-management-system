package lms.identity.tenant.application;

import java.time.Instant;
import java.util.UUID;
import lms.identity.tenant.domain.TenantModuleEntry;

/** Trạng thái một module của tenant; updatedAt/updatedBy null khi module chưa từng được bật. */
public record TenantModuleResponse(String module, boolean enabled, Instant updatedAt, UUID updatedBy) {

    static TenantModuleResponse from(TenantModuleEntry e) {
        return new TenantModuleResponse(e.module().name(), e.enabled(), e.updatedAt(), e.updatedBy());
    }
}

package lms.identity.tenant.infrastructure.persistence;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Khóa chính kép (tenant_id, module) của tenant_modules. */
class TenantModuleId implements Serializable {

    private UUID tenantId;
    private String module;

    protected TenantModuleId() {
    }

    TenantModuleId(UUID tenantId, String module) {
        this.tenantId = tenantId;
        this.module = module;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TenantModuleId other
                && Objects.equals(tenantId, other.tenantId) && Objects.equals(module, other.module);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, module);
    }
}

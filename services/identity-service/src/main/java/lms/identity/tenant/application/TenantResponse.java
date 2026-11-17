package lms.identity.tenant.application;

import java.util.List;
import java.util.UUID;
import lms.identity.tenant.domain.ModuleCode;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantStatus;

/**
 * memberCount: số thành viên ACTIVE; modules: module đang bật (ADR-0009); suspensionCause: null khi ACTIVE, ngược lại
 * MANUAL | CONTRACT_EXPIRED | CONTRACT_CANCELLED (ADR-0012).
 */
public record TenantResponse(UUID id, String code, String name, UUID organizationId, TenantStatus status,
                             long memberCount, List<String> modules, String suspensionCause) {

    public TenantResponse(UUID id, String code, String name, UUID organizationId, TenantStatus status,
                          long memberCount, List<String> modules) {
        this(id, code, name, organizationId, status, memberCount, modules, null);
    }

    public static TenantResponse from(Tenant t, long memberCount, List<ModuleCode> modules) {
        return new TenantResponse(t.id(), t.code(), t.name(), t.organizationId(), t.status(), memberCount,
                modules.stream().map(ModuleCode::name).toList(),
                t.suspensionCause() == null ? null : t.suspensionCause().name());
    }
}

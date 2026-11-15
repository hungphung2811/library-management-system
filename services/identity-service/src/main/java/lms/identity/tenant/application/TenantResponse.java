package lms.identity.tenant.application;

import java.util.UUID;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantStatus;

/** memberCount: số thành viên ACTIVE. */
public record TenantResponse(UUID id, String code, String name, UUID organizationId, TenantStatus status,
                             long memberCount) {

    public static TenantResponse from(Tenant t, long memberCount) {
        return new TenantResponse(t.id(), t.code(), t.name(), t.organizationId(), t.status(), memberCount);
    }
}

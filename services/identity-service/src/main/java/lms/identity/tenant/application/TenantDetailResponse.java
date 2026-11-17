package lms.identity.tenant.application;

import java.util.List;
import java.util.UUID;
import lms.identity.tenant.domain.TenantStatus;

/**
 * Chi tiết tenant cho người vận hành: thông tin tenant, số thành viên, các quản trị viên và module đang bật của tenant;
 * suspensionCause null khi ACTIVE (ADR-0012).
 */
public record TenantDetailResponse(UUID id, String code, String name, UUID organizationId, TenantStatus status,
                                   long memberCount, List<UUID> adminUserIds, List<String> modules,
                                   String suspensionCause) {

    public TenantDetailResponse(UUID id, String code, String name, UUID organizationId, TenantStatus status,
                                long memberCount, List<UUID> adminUserIds, List<String> modules) {
        this(id, code, name, organizationId, status, memberCount, adminUserIds, modules, null);
    }
}

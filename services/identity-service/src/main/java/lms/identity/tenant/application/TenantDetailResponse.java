package lms.identity.tenant.application;

import java.util.List;
import java.util.UUID;
import lms.identity.tenant.domain.TenantStatus;

/** Chi tiết tenant cho người vận hành: thông tin tenant, số thành viên và các quản trị viên của tenant. */
public record TenantDetailResponse(UUID id, String code, String name, UUID organizationId, TenantStatus status,
                                   long memberCount, List<UUID> adminUserIds) {
}

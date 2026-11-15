package lms.circulation.tenant.application;

import java.util.UUID;
import lms.circulation.tenant.domain.KnownTenantStatus;

/** Danh bạ tenant (bảng toàn cục known_tenants), nuôi bởi event TenantCreated/TenantStatusChanged của identity. */
public interface KnownTenantsPort {

    /** Thêm hoặc cập nhật trạng thái tenant (idempotent). */
    void upsert(UUID tenantId, KnownTenantStatus status);
}

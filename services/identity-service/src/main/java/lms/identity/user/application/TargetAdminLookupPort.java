package lms.identity.user.application;

import java.util.UUID;

/**
 * Cổng sang context access-control: người dùng có role quản trị (is_admin) TRONG TENANT HIỆN TẠI hay không (tra lúc
 * gọi, theo TenantContext). Cài đặt ở phía access-control để user không phải biết access-control.
 */
public interface TargetAdminLookupPort {
    boolean isTenantAdmin(UUID userId);
}

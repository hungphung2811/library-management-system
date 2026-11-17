package lms.identity.tenant.application;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Cổng sang context accesscontrol (role thuộc tenant). Mọi thao tác áp cho tenant HIỆN TẠI trong TenantContext (bảng
 * roles/user_roles chịu RLS FORCE), nên người gọi phải chạy trong {@code TenantContext.runAs(tenantId)} và transaction
 * phải bắt đầu bên trong đó.
 */
public interface TenantRoleProvisioningPort {

    /**
     * Sao chép role_templates thành role của tenant hiện tại: mẫu lõi (module NULL) cộng mẫu của các module trong
     * {@code modules} (mã như LENDING); mẫu có cờ admin thành role isAdmin; bỏ qua mẫu mà tenant đã có role cùng tên.
     * Trả số role tạo.
     */
    int seedFromTemplates(Collection<String> modules);

    /**
     * Bật module (ADR-0009): chỉ sao chép mẫu của đúng module đó (không đụng mẫu lõi), bỏ qua mẫu mà tenant đã có role
     * cùng tên để idempotent và không ghi đè chỉnh sửa của quản trị tenant. Trả số role tạo.
     */
    int seedModuleTemplates(String module);

    /** Gán role quản trị (isAdmin) của tenant hiện tại cho người dùng; idempotent. */
    void grantTenantAdmin(UUID userId);

    /** Thu hồi mọi role quản trị của tenant hiện tại khỏi người dùng; false nếu người đó không phải quản trị viên. */
    boolean revokeTenantAdmin(UUID userId);

    /** Người dùng có ít nhất một role quản trị trong tenant hiện tại. */
    List<UUID> listTenantAdmins();
}

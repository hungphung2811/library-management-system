package lms.identity.accesscontrol.domain;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Aggregate Root: nhóm permission. Quan hệ Role - Permission nằm trong aggregate này.
 * Role có cờ admin (is_admin) cho biết đây là role quản trị toàn quyền: người dùng có role này được bỏ qua kiểm tra
 * permission theo endpoint. Cờ nằm trong dữ liệu, không gắn với tên role nào.
 */
public class Role {

    private final RoleId id;
    private final String name;
    private final String description;
    private final boolean admin;
    private final Set<String> permissionCodes;
    /** Tenant sở hữu (chỉ điền khi đọc từ kho; null với đối tượng vừa tạo). */
    private final java.util.UUID tenantId;
    /**
     * Role mà tài khoản tự đăng ký (storefront) nhận. Role quản trị không bao giờ mang cờ này (luật domain, DB có
     * CHECK tương ứng); mỗi tenant tối đa một role mang cờ (chỉ mục duy nhất ở DB, use case kiểm trước).
     */
    private final boolean signupDefault;

    private Role(RoleId id, String name, String description, boolean admin, Collection<String> permissionCodes,
                 java.util.UUID tenantId, boolean signupDefault) {
        NamingRule.requireUpperSnake(name, "Tên role");
        if (description == null || description.isBlank()) {
            throw new InvalidAccessControlException("Mô tả role không được trống");
        }
        if (admin && signupDefault) {
            throw new InvalidAccessControlException("Role quản trị (isAdmin) không được đặt làm role đăng ký mặc định");
        }
        this.id = id;
        this.name = name;
        this.description = description;
        this.admin = admin;
        this.signupDefault = signupDefault;
        this.permissionCodes = new LinkedHashSet<>(permissionCodes);
        this.tenantId = tenantId;
    }

    public static Role create(String name, String description) {
        return create(name, description, false);
    }

    public static Role create(String name, String description, boolean admin) {
        return new Role(RoleId.newId(), name, description, admin, Set.of(), null, false);
    }

    public static Role restore(RoleId id, String name, String description, Collection<String> permissionCodes) {
        return restore(id, name, description, false, permissionCodes);
    }

    public static Role restore(RoleId id, String name, String description, boolean admin,
                               Collection<String> permissionCodes) {
        return new Role(id, name, description, admin, permissionCodes, null, false);
    }

    public static Role restore(RoleId id, String name, String description, boolean admin,
                               Collection<String> permissionCodes, java.util.UUID tenantId) {
        return new Role(id, name, description, admin, permissionCodes, tenantId, false);
    }

    public static Role restore(RoleId id, String name, String description, boolean admin,
                               Collection<String> permissionCodes, java.util.UUID tenantId, boolean signupDefault) {
        return new Role(id, name, description, admin, permissionCodes, tenantId, signupDefault);
    }

    /** Đổi tên/mô tả, giữ id, cờ admin và permission (không cho đổi cờ admin qua đường này). */
    public Role rename(String newName, String newDescription) {
        return new Role(id, newName, newDescription, admin, permissionCodes, tenantId, signupDefault);
    }

    /** Bật/tắt cờ role đăng ký mặc định, giữ nguyên phần còn lại. Role quản trị bật cờ thì ném InvalidAccessControlException. */
    public Role withSignupDefault(boolean value) {
        return new Role(id, name, description, admin, permissionCodes, tenantId, value);
    }

    /** Idempotent: cấp lại permission đã có không gây lỗi. */
    public void grant(String permissionCode) {
        permissionCodes.add(NamingRule.requirePermissionKey(permissionCode, "Mã permission"));
    }

    public void revoke(String permissionCode) {
        if (!permissionCodes.remove(permissionCode)) {
            throw new InvalidAccessControlException("Role " + name + " không có permission " + permissionCode);
        }
    }

    public RoleId id() { return id; }
    public String name() { return name; }
    public String description() { return description; }
    public boolean isAdmin() { return admin; }
    public java.util.UUID tenantId() { return tenantId; }
    public boolean isSignupDefault() { return signupDefault; }
    public Set<String> permissionCodes() { return Set.copyOf(permissionCodes); }
}

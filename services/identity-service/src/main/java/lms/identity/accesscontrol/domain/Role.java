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

    private Role(RoleId id, String name, String description, boolean admin, Collection<String> permissionCodes) {
        NamingRule.requireUpperSnake(name, "Tên role");
        if (description == null || description.isBlank()) {
            throw new InvalidAccessControlException("Mô tả role không được trống");
        }
        this.id = id;
        this.name = name;
        this.description = description;
        this.admin = admin;
        this.permissionCodes = new LinkedHashSet<>(permissionCodes);
    }

    public static Role create(String name, String description) {
        return create(name, description, false);
    }

    public static Role create(String name, String description, boolean admin) {
        return new Role(RoleId.newId(), name, description, admin, Set.of());
    }

    public static Role restore(RoleId id, String name, String description, Collection<String> permissionCodes) {
        return restore(id, name, description, false, permissionCodes);
    }

    public static Role restore(RoleId id, String name, String description, boolean admin,
                               Collection<String> permissionCodes) {
        return new Role(id, name, description, admin, permissionCodes);
    }

    /** Đổi tên/mô tả, giữ id, cờ admin và permission (không cho đổi cờ admin qua đường này). */
    public Role rename(String newName, String newDescription) {
        return new Role(id, newName, newDescription, admin, permissionCodes);
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
    public Set<String> permissionCodes() { return Set.copyOf(permissionCodes); }
}

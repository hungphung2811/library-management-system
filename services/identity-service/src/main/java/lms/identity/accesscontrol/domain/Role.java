package lms.identity.accesscontrol.domain;

import lms.identity.accesscontrol.domain.InvalidAccessControlException;
import lms.identity.accesscontrol.domain.NamingRule;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** Aggregate Root: nhóm permission. Quan hệ Role - Permission nằm trong aggregate này. */
public class Role {

    private final RoleId id;
    private final String name;
    private final String description;
    private final Set<String> permissionCodes;

    private Role(RoleId id, String name, String description, Collection<String> permissionCodes) {
        NamingRule.requireUpperSnake(name, "Tên role");
        if (description == null || description.isBlank()) {
            throw new InvalidAccessControlException("Mô tả role không được trống");
        }
        this.id = id;
        this.name = name;
        this.description = description;
        this.permissionCodes = new LinkedHashSet<>(permissionCodes);
    }

    public static Role create(String name, String description) {
        return new Role(RoleId.newId(), name, description, Set.of());
    }

    public static Role restore(RoleId id, String name, String description, Collection<String> permissionCodes) {
        return new Role(id, name, description, permissionCodes);
    }

    /** Idempotent: cấp lại permission đã có không gây lỗi. */
    public void grant(String permissionCode) {
        permissionCodes.add(NamingRule.requireUpperSnake(permissionCode, "Mã permission"));
    }

    public void revoke(String permissionCode) {
        if (!permissionCodes.remove(permissionCode)) {
            throw new InvalidAccessControlException("Role " + name + " không có permission " + permissionCode);
        }
    }

    public RoleId id() { return id; }
    public String name() { return name; }
    public String description() { return description; }
    public Set<String> permissionCodes() { return Set.copyOf(permissionCodes); }
}

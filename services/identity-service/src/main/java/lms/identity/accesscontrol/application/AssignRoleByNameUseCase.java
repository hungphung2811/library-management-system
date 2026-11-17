package lms.identity.accesscontrol.application;

import java.util.UUID;
import lms.identity.accesscontrol.application.AccessCommands.UserRole;
import lms.identity.accesscontrol.domain.AccessControlNotFoundException;
import lms.identity.accesscontrol.domain.Role;
import lms.identity.accesscontrol.domain.RoleRepository;
import lms.identity.accesscontrol.domain.UserRoleRepository;

/**
 * Gán role của tenant hiện tại cho người dùng theo TÊN role (role mẫu đã được chép khi tạo tenant), qua
 * {@link UserRoleUseCase}. Dùng để seed dữ liệu demo idempotent (ADR-0018). Gọi trong TenantContext của tenant đích.
 */
public class AssignRoleByNameUseCase {

    private final RoleRepository roles;
    private final UserRoleRepository assignments;
    private final UserRoleUseCase userRoles;

    public AssignRoleByNameUseCase(RoleRepository roles, UserRoleRepository assignments, UserRoleUseCase userRoles) {
        this.roles = roles;
        this.assignments = assignments;
        this.userRoles = userRoles;
    }

    /**
     * @return true nếu vừa gán, false nếu người dùng đã có role đó
     * @throws AccessControlNotFoundException nếu tenant chưa có role tên đó
     */
    public boolean execute(UUID userId, String roleName) {
        Role role = roles.findAll().stream().filter(r -> r.name().equals(roleName)).findFirst()
                .orElseThrow(() -> new AccessControlNotFoundException("Tenant chưa có role " + roleName));
        boolean held = assignments.findByUserId(userId).map(a -> a.roleIds().contains(role.id())).orElse(false);
        if (held) return false;
        // người gọi là bước seed của người vận hành: được gán cả role quản trị
        userRoles.assign(new UserRole(userId, role.id().value(), true));
        return true;
    }
}

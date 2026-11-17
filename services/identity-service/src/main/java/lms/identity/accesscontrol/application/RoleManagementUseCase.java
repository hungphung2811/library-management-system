package lms.identity.accesscontrol.application;

import lms.identity.accesscontrol.application.AccessDtos.RoleResponse;
import lms.identity.accesscontrol.application.AccessMapper;
import lms.identity.accesscontrol.application.AccessCommands.CreateRole;
import lms.identity.accesscontrol.application.AccessCommands.DeleteRole;
import lms.identity.accesscontrol.application.AccessCommands.GrantPermission;
import lms.identity.accesscontrol.application.AccessCommands.UpdateRole;
import lms.identity.accesscontrol.domain.RoleConflictException;
import lms.identity.accesscontrol.domain.UserRoleRepository;
import lms.identity.accesscontrol.application.SaveRolePort;
import lms.identity.accesscontrol.domain.AccessControlForbiddenException;
import lms.identity.accesscontrol.domain.AccessControlNotFoundException;
import lms.identity.accesscontrol.domain.DuplicateAccessControlException;
import lms.identity.accesscontrol.domain.Role;
import lms.identity.accesscontrol.domain.RoleId;
import lms.identity.accesscontrol.domain.PermissionRepository;
import lms.identity.accesscontrol.domain.RoleRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class RoleManagementUseCase {

    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final SaveRolePort savePort;
    private final UserRoleRepository assignments;

    public RoleManagementUseCase(RoleRepository roles, PermissionRepository permissions, SaveRolePort savePort,
                                 UserRoleRepository assignments) {
        this.roles = roles;
        this.permissions = permissions;
        this.savePort = savePort;
        this.assignments = assignments;
    }

    @Transactional
    public RoleResponse create(CreateRole command) {
        if (command.admin() && !command.requestedByAdmin()) {
            throw new AccessControlForbiddenException("Chỉ quản trị viên mới tạo được role quản trị (isAdmin)");
        }
        Role role = Role.create(command.name(), command.description(), command.admin());
        if (roles.existsByName(role.name())) {
            throw new DuplicateAccessControlException("Role đã tồn tại: " + role.name());
        }
        return AccessMapper.toResponse(savePort.save(role));
    }

    @Transactional
    public RoleResponse update(UpdateRole command) {
        Role role = load(command.roleId());
        requireAdminForAdminRole(role, command.requestedByAdmin(), "sửa");
        Role updated = role.rename(command.name(), command.description());
        if (!updated.name().equals(role.name()) && roles.existsByName(updated.name())) {
            throw new DuplicateAccessControlException("Role đã tồn tại: " + updated.name());
        }
        if (command.signupDefault() != null && command.signupDefault() != role.isSignupDefault()) {
            if (command.signupDefault()) {
                if (!command.requestedByAdmin()) {
                    throw new AccessControlForbiddenException(
                            "Chỉ quản trị viên mới đặt được role đăng ký mặc định (signupDefault)");
                }
                // withSignupDefault ném InvalidAccessControlException với role quản trị (không bao giờ mang cờ)
                updated = updated.withSignupDefault(true);
                if (roles.findAll().stream().anyMatch(r -> r.isSignupDefault() && !r.id().equals(role.id()))) {
                    throw new RoleConflictException("SIGNUP_ROLE_EXISTS",
                            "Tenant đã có role đăng ký mặc định khác, hãy tắt cờ đó trước");
                }
            } else {
                updated = updated.withSignupDefault(false);
            }
        }
        return AccessMapper.toResponse(savePort.save(updated));
    }

    /**
     * Xóa cứng role (role_permissions xóa theo). Chặn khi còn user được gán (ROLE_IN_USE) hoặc là role quản trị
     * duy nhất của tenant (LAST_ADMIN_ROLE). Token đã phát hành vẫn mang permission cũ đến khi hết hạn/refresh.
     */
    @Transactional
    public void delete(DeleteRole command) {
        Role role = load(command.roleId());
        requireAdminForAdminRole(role, command.requestedByAdmin(), "xóa");
        if (role.isAdmin() && roles.findAll().stream().filter(Role::isAdmin).count() <= 1) {
            throw new RoleConflictException("LAST_ADMIN_ROLE", "Không thể xóa role quản trị duy nhất: " + role.name());
        }
        if (assignments.existsByRoleId(role.id().value())) {
            throw new RoleConflictException("ROLE_IN_USE", "Role đang được gán cho người dùng: " + role.name());
        }
        savePort.delete(role);
    }

    private static void requireAdminForAdminRole(Role role, boolean callerIsAdmin, String action) {
        if (role.isAdmin() && !callerIsAdmin) {
            throw new AccessControlForbiddenException("Chỉ quản trị viên mới " + action + " được role quản trị (isAdmin)");
        }
    }

    @Transactional
    public RoleResponse grantPermission(GrantPermission command) {
        Role role = load(command.roleId());
        if (role.isSignupDefault() && !command.requestedByAdmin()) {
            throw new AccessControlForbiddenException(
                    "Chỉ quản trị viên mới thêm được khóa vào role đăng ký mặc định (signupDefault)");
        }
        if (!permissions.existsByCode(command.permissionCode())) {
            throw new AccessControlNotFoundException("Không tìm thấy permission: " + command.permissionCode());
        }
        role.grant(command.permissionCode());
        return AccessMapper.toResponse(savePort.save(role));
    }

    @Transactional
    public RoleResponse revokePermission(UUID roleId, String permissionCode) {
        Role role = load(roleId);
        role.revoke(permissionCode);
        return AccessMapper.toResponse(savePort.save(role));
    }

    @Transactional(readOnly = true)
    public RoleResponse get(UUID roleId) {
        return AccessMapper.toResponse(load(roleId));
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> list() {
        return roles.findAll().stream().map(AccessMapper::toResponse).toList();
    }

    private Role load(UUID roleId) {
        return roles.findById(new RoleId(roleId))
                .orElseThrow(() -> new AccessControlNotFoundException("Không tìm thấy role: " + roleId));
    }
}

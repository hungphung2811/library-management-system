package lms.identity.accesscontrol.application;

import lms.identity.accesscontrol.application.AccessDtos.RoleResponse;
import lms.identity.accesscontrol.application.AccessMapper;
import lms.identity.accesscontrol.application.AccessCommands.CreateRole;
import lms.identity.accesscontrol.application.AccessCommands.GrantPermission;
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

    public RoleManagementUseCase(RoleRepository roles, PermissionRepository permissions, SaveRolePort savePort) {
        this.roles = roles;
        this.permissions = permissions;
        this.savePort = savePort;
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
    public RoleResponse grantPermission(GrantPermission command) {
        Role role = load(command.roleId());
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

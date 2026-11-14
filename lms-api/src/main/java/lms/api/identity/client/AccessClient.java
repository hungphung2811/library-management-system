package lms.api.identity.client;

import lms.api.identity.dto.AccessDtos.AssignRoleRequest;
import lms.api.identity.dto.AccessDtos.CreatePermissionRequest;
import lms.api.identity.dto.AccessDtos.CreateRoleRequest;
import lms.api.identity.dto.AccessDtos.GrantPermissionRequest;
import lms.api.identity.dto.AccessDtos.PermissionView;
import lms.api.identity.dto.AccessDtos.RoleView;
import lms.api.identity.dto.AccessDtos.UserAccessView;
import java.util.List;
import java.util.UUID;

/** Cổng sang API phân quyền của identity-service. */
public interface AccessClient {

    PermissionView createPermission(CreatePermissionRequest request);

    List<PermissionView> listPermissions();

    RoleView createRole(CreateRoleRequest request);

    List<RoleView> listRoles();

    RoleView getRole(UUID roleId);

    RoleView grantPermission(UUID roleId, GrantPermissionRequest request);

    /** permissionCode có thể chứa dấu "/" (khóa Controller/method). */
    RoleView revokePermission(UUID roleId, String permissionCode);

    void assignRole(UUID userId, AssignRoleRequest request);

    void revokeRole(UUID userId, UUID roleId);

    UserAccessView getUserAccess(UUID userId);
}

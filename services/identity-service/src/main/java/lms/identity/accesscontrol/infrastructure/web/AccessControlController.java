package lms.identity.accesscontrol.infrastructure.web;

import lms.identity.accesscontrol.application.AccessDtos.AssignRoleRequest;
import lms.identity.accesscontrol.application.AccessDtos.CreatePermissionRequest;
import lms.identity.accesscontrol.application.AccessDtos.CreateRoleRequest;
import lms.identity.accesscontrol.application.AccessDtos.GrantPermissionRequest;
import lms.identity.accesscontrol.application.AccessDtos.PermissionResponse;
import lms.identity.accesscontrol.application.AccessDtos.RoleResponse;
import lms.identity.accesscontrol.application.AccessDtos.UserAccessResponse;
import lms.identity.accesscontrol.application.AccessCommands;
import lms.identity.accesscontrol.application.GetUserAccessUseCase;
import lms.identity.accesscontrol.application.PermissionManagementUseCase;
import lms.identity.accesscontrol.application.RoleManagementUseCase;
import lms.identity.accesscontrol.application.UserRoleUseCase;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AccessControlController {

    private final PermissionManagementUseCase permissions;
    private final RoleManagementUseCase roles;
    private final UserRoleUseCase userRoles;
    private final GetUserAccessUseCase userAccess;

    public AccessControlController(PermissionManagementUseCase permissions, RoleManagementUseCase roles,
                                   UserRoleUseCase userRoles, GetUserAccessUseCase userAccess) {
        this.permissions = permissions;
        this.roles = roles;
        this.userRoles = userRoles;
        this.userAccess = userAccess;
    }

    @PostMapping("/permissions")
    public ResponseEntity<PermissionResponse> createPermission(@Valid @RequestBody CreatePermissionRequest request) {
        PermissionResponse response = permissions.create(
                new AccessCommands.CreatePermission(request.code(), request.description()));
        return ResponseEntity.created(URI.create("/api/permissions/" + response.code())).body(response);
    }

    @GetMapping("/permissions")
    public List<PermissionResponse> listPermissions() {
        return permissions.list();
    }

    @PostMapping("/roles")
    public ResponseEntity<RoleResponse> createRole(@Valid @RequestBody CreateRoleRequest request) {
        RoleResponse response = roles.create(new AccessCommands.CreateRole(request.name(), request.description()));
        return ResponseEntity.created(URI.create("/api/roles/" + response.id())).body(response);
    }

    @GetMapping("/roles")
    public List<RoleResponse> listRoles() {
        return roles.list();
    }

    @GetMapping("/roles/{roleId}")
    public RoleResponse getRole(@PathVariable UUID roleId) {
        return roles.get(roleId);
    }

    @PostMapping("/roles/{roleId}/permissions")
    public RoleResponse grantPermission(@PathVariable UUID roleId, @Valid @RequestBody GrantPermissionRequest request) {
        return roles.grantPermission(new AccessCommands.GrantPermission(roleId, request.permissionCode()));
    }

    @DeleteMapping("/roles/{roleId}/permissions/{permissionCode}")
    public RoleResponse revokePermission(@PathVariable UUID roleId, @PathVariable String permissionCode) {
        return roles.revokePermission(roleId, permissionCode);
    }

    /** Dùng prefix /api/access để không đụng route /api/users của user-service trên gateway. */
    @PostMapping("/access/users/{userId}/roles")
    public ResponseEntity<Void> assignRole(@PathVariable UUID userId, @Valid @RequestBody AssignRoleRequest request) {
        userRoles.assign(new AccessCommands.UserRole(userId, request.roleId()));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/access/users/{userId}/roles/{roleId}")
    public ResponseEntity<Void> revokeRole(@PathVariable UUID userId, @PathVariable UUID roleId) {
        userRoles.revoke(new AccessCommands.UserRole(userId, roleId));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/access/users/{userId}")
    public UserAccessResponse getUserAccess(@PathVariable UUID userId) {
        return userAccess.execute(userId);
    }
}

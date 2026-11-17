package lms.identity.accesscontrol.infrastructure.web;

import lms.identity.accesscontrol.application.AccessDtos.AssignRoleRequest;
import lms.identity.accesscontrol.application.AccessDtos.CreatePermissionRequest;
import lms.identity.accesscontrol.application.AccessDtos.CreateRoleRequest;
import lms.identity.accesscontrol.application.AccessDtos.GrantPermissionRequest;
import lms.identity.accesscontrol.application.AccessDtos.PermissionResponse;
import lms.identity.accesscontrol.application.AccessDtos.RoleResponse;
import lms.identity.accesscontrol.application.AccessDtos.UpdateRoleRequest;
import lms.identity.accesscontrol.application.AccessDtos.UserAccessResponse;
import lms.identity.accesscontrol.application.AccessCommands;
import lms.identity.accesscontrol.application.GetUserAccessUseCase;
import lms.identity.accesscontrol.application.PermissionManagementUseCase;
import lms.identity.accesscontrol.application.RoleManagementUseCase;
import lms.identity.accesscontrol.application.UserRoleUseCase;
import lms.security.JwtClaims;
import lms.security.permission.CrossTenantReadable;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quản lý role, permission và gán role. Không gắn annotation quyền nào: mỗi method dùng khóa tự sinh
 * {@code AccessControlController/tênPhươngThức} (xem PermissionInterceptor ở security-common).
 */
@RestController
@RequestMapping("/internal")
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
        return ResponseEntity.created(URI.create("/internal/permissions/" + response.code())).body(response);
    }

    @CrossTenantReadable
    @GetMapping("/permissions")
    public List<PermissionResponse> listPermissions() {
        return permissions.list();
    }

    /** isAdmin trong request là ô tích "role quản trị"; chỉ quản trị viên mới tạo được role như vậy. */
    @PostMapping("/roles")
    public ResponseEntity<RoleResponse> createRole(@AuthenticationPrincipal Jwt jwt,
                                                   @Valid @RequestBody CreateRoleRequest request) {
        RoleResponse response = roles.create(new AccessCommands.CreateRole(request.name(), request.description(),
                Boolean.TRUE.equals(request.isAdmin()), callerIsAdmin(jwt)));
        return ResponseEntity.created(URI.create("/internal/roles/" + response.id())).body(response);
    }

    @CrossTenantReadable
    @GetMapping("/roles")
    public List<RoleResponse> listRoles() {
        return roles.list();
    }

    @GetMapping("/roles/{roleId}")
    public RoleResponse getRole(@PathVariable UUID roleId) {
        return roles.get(roleId);
    }

    /** Đổi tên/mô tả và (tùy chọn) cờ signupDefault. Role isAdmin chỉ quản trị viên được sửa; cờ isAdmin không đổi được. */
    @PutMapping("/roles/{roleId}")
    public RoleResponse updateRole(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roleId,
                                   @Valid @RequestBody UpdateRoleRequest request) {
        return roles.update(new AccessCommands.UpdateRole(roleId, request.name(), request.description(),
                callerIsAdmin(jwt), request.signupDefault()));
    }

    /** Xóa cứng; 409 ROLE_IN_USE khi còn người dùng được gán, LAST_ADMIN_ROLE khi là role quản trị duy nhất. */
    @DeleteMapping("/roles/{roleId}")
    public ResponseEntity<Void> deleteRole(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roleId) {
        roles.delete(new AccessCommands.DeleteRole(roleId, callerIsAdmin(jwt)));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/roles/{roleId}/permissions")
    public RoleResponse grantPermission(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roleId,
                                        @Valid @RequestBody GrantPermissionRequest request) {
        return roles.grantPermission(new AccessCommands.GrantPermission(roleId, request.permissionCode(),
                callerIsAdmin(jwt)));
    }

    /** permissionCode có thể chứa dấu "/" (khóa Controller/method) nên dùng pattern bắt phần còn lại của đường dẫn. */
    @DeleteMapping("/roles/{roleId}/permissions/**")
    public RoleResponse revokePermission(@PathVariable UUID roleId, jakarta.servlet.http.HttpServletRequest request) {
        String prefix = "/internal/roles/" + roleId + "/permissions/";
        String uri = java.net.URLDecoder.decode(request.getRequestURI(), java.nio.charset.StandardCharsets.UTF_8);
        return roles.revokePermission(roleId, uri.substring(uri.indexOf(prefix) + prefix.length()));
    }

    /** Dùng prefix /internal/access để không trùng route /internal/users. */
    @PostMapping("/access/users/{userId}/roles")
    public ResponseEntity<Void> assignRole(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
                                           @Valid @RequestBody AssignRoleRequest request) {
        userRoles.assign(new AccessCommands.UserRole(userId, request.roleId(), callerIsAdmin(jwt)));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/access/users/{userId}/roles/{roleId}")
    public ResponseEntity<Void> revokeRole(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID userId,
                                           @PathVariable UUID roleId) {
        userRoles.revoke(new AccessCommands.UserRole(userId, roleId, callerIsAdmin(jwt)));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/access/users/{userId}")
    public UserAccessResponse getUserAccess(@PathVariable UUID userId) {
        return userAccess.execute(userId);
    }

    private static boolean callerIsAdmin(Jwt jwt) {
        return jwt != null && Boolean.TRUE.equals(jwt.getClaim(JwtClaims.ADMIN));
    }
}

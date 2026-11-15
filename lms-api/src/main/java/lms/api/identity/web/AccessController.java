package lms.api.identity.web;

import lms.api.identity.client.AccessClient;
import lms.api.identity.dto.AccessDtos.AssignRoleRequest;
import lms.api.identity.dto.AccessDtos.CreatePermissionRequest;
import lms.api.identity.dto.AccessDtos.CreateRoleRequest;
import lms.api.identity.dto.AccessDtos.GrantPermissionRequest;
import lms.api.identity.dto.AccessDtos.PermissionView;
import lms.api.identity.dto.AccessDtos.RoleView;
import lms.api.identity.dto.AccessDtos.UpdateRoleRequest;
import lms.api.identity.dto.AccessDtos.UserAccessView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.net.URLDecoder;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API role, permission và gán role cho FE. Quyền do identity-service kiểm trên token được chuyển tiếp. */
@RestController
@RequestMapping("/api")
public class AccessController {

    private final AccessClient access;

    public AccessController(AccessClient access) {
        this.access = access;
    }

    @PostMapping("/permissions")
    public ResponseEntity<PermissionView> createPermission(@Valid @RequestBody CreatePermissionRequest request) {
        PermissionView created = access.createPermission(request);
        return ResponseEntity.created(URI.create("/api/permissions/" + created.code())).body(created);
    }

    @GetMapping("/permissions")
    public List<PermissionView> listPermissions() {
        return access.listPermissions();
    }

    @PostMapping("/roles")
    public ResponseEntity<RoleView> createRole(@Valid @RequestBody CreateRoleRequest request) {
        RoleView created = access.createRole(request);
        return ResponseEntity.created(URI.create("/api/roles/" + created.id())).body(created);
    }

    @GetMapping("/roles")
    public List<RoleView> listRoles() {
        return access.listRoles();
    }

    @GetMapping("/roles/{roleId}")
    public RoleView getRole(@PathVariable UUID roleId) {
        return access.getRole(roleId);
    }

    @PutMapping("/roles/{roleId}")
    public RoleView updateRole(@PathVariable UUID roleId, @Valid @RequestBody UpdateRoleRequest request) {
        return access.updateRole(roleId, request);
    }

    @DeleteMapping("/roles/{roleId}")
    public ResponseEntity<Void> deleteRole(@PathVariable UUID roleId) {
        access.deleteRole(roleId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/roles/{roleId}/permissions")
    public RoleView grantPermission(@PathVariable UUID roleId, @Valid @RequestBody GrantPermissionRequest request) {
        return access.grantPermission(roleId, request);
    }

    /** permissionCode có thể chứa dấu "/" (khóa Controller/method) nên bắt phần còn lại của đường dẫn. */
    @DeleteMapping("/roles/{roleId}/permissions/**")
    public RoleView revokePermission(@PathVariable UUID roleId, HttpServletRequest request) {
        String prefix = "/api/roles/" + roleId + "/permissions/";
        String uri = URLDecoder.decode(request.getRequestURI(), StandardCharsets.UTF_8);
        return access.revokePermission(roleId, uri.substring(uri.indexOf(prefix) + prefix.length()));
    }

    @PostMapping("/access/users/{userId}/roles")
    public ResponseEntity<Void> assignRole(@PathVariable UUID userId, @Valid @RequestBody AssignRoleRequest request) {
        access.assignRole(userId, request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/access/users/{userId}/roles/{roleId}")
    public ResponseEntity<Void> revokeRole(@PathVariable UUID userId, @PathVariable UUID roleId) {
        access.revokeRole(userId, roleId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/access/users/{userId}")
    public UserAccessView getUserAccess(@PathVariable UUID userId) {
        return access.getUserAccess(userId);
    }
}

package lms.api.identity.client.http;

import lms.api.identity.client.AccessClient;
import lms.api.identity.dto.AccessDtos.AssignRoleRequest;
import lms.api.identity.dto.AccessDtos.CreatePermissionRequest;
import lms.api.identity.dto.AccessDtos.CreateRoleRequest;
import lms.api.identity.dto.AccessDtos.GrantPermissionRequest;
import lms.api.identity.dto.AccessDtos.PermissionView;
import lms.api.identity.dto.AccessDtos.RoleView;
import lms.api.identity.dto.AccessDtos.UpdateRoleRequest;
import lms.api.identity.dto.AccessDtos.UserAccessView;
import java.util.List;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/** Gọi API nội bộ /internal/permissions, /internal/roles, /internal/access của identity-service. */
public class HttpAccessClient implements AccessClient {

    private final RestClient client;

    public HttpAccessClient(RestClient client) {
        this.client = client;
    }

    @Override
    public PermissionView createPermission(CreatePermissionRequest request) {
        return client.post().uri("/internal/permissions").body(request).retrieve().body(PermissionView.class);
    }

    @Override
    public List<PermissionView> listPermissions(String tenantId) {
        return client.get().uri(b -> tenantQuery(b.path("/internal/permissions"), tenantId).build()).retrieve()
                .body(new ParameterizedTypeReference<List<PermissionView>>() { });
    }

    @Override
    public RoleView createRole(CreateRoleRequest request) {
        return client.post().uri("/internal/roles").body(request).retrieve().body(RoleView.class);
    }

    @Override
    public List<RoleView> listRoles(String tenantId) {
        return client.get().uri(b -> tenantQuery(b.path("/internal/roles"), tenantId).build()).retrieve()
                .body(new ParameterizedTypeReference<List<RoleView>>() { });
    }

    /** Chỉ thêm query tenantId khi có giá trị (ADR-0008); không diễn giải, service tự kiểm. */
    private static UriBuilder tenantQuery(UriBuilder b, String tenantId) {
        return (tenantId == null || tenantId.isBlank()) ? b : b.queryParam("tenantId", tenantId);
    }

    @Override
    public RoleView getRole(UUID roleId) {
        return client.get().uri("/internal/roles/{id}", roleId).retrieve().body(RoleView.class);
    }

    @Override
    public RoleView updateRole(UUID roleId, UpdateRoleRequest request) {
        return client.put().uri("/internal/roles/{id}", roleId).body(request).retrieve().body(RoleView.class);
    }

    @Override
    public void deleteRole(UUID roleId) {
        client.delete().uri("/internal/roles/{id}", roleId).retrieve().toBodilessEntity();
    }

    @Override
    public RoleView grantPermission(UUID roleId, GrantPermissionRequest request) {
        return client.post().uri("/internal/roles/{id}/permissions", roleId).body(request).retrieve()
                .body(RoleView.class);
    }

    @Override
    public RoleView revokePermission(UUID roleId, String permissionCode) {
        // Giữ dấu "/" trong khóa Controller/method thành các segment thật (không mã hóa %2F, Tomcat từ chối %2F)
        return client.delete().uri(b -> b.path("/internal/roles/{id}/permissions")
                        .pathSegment(permissionCode.split("/")).build(roleId))
                .retrieve().body(RoleView.class);
    }

    @Override
    public void assignRole(UUID userId, AssignRoleRequest request) {
        client.post().uri("/internal/access/users/{id}/roles", userId).body(request).retrieve().toBodilessEntity();
    }

    @Override
    public void revokeRole(UUID userId, UUID roleId) {
        client.delete().uri("/internal/access/users/{u}/roles/{r}", userId, roleId).retrieve().toBodilessEntity();
    }

    @Override
    public UserAccessView getUserAccess(UUID userId) {
        return client.get().uri("/internal/access/users/{id}", userId).retrieve().body(UserAccessView.class);
    }
}

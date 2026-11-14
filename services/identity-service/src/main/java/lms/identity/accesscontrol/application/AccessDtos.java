package lms.identity.accesscontrol.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** Request/response của REST API. */
public final class AccessDtos {

    private AccessDtos() {
    }

    public record CreateRoleRequest(@NotBlank String name, @NotBlank String description) {
    }

    public record CreatePermissionRequest(@NotBlank String code, @NotBlank String description) {
    }

    public record GrantPermissionRequest(@NotBlank String permissionCode) {
    }

    public record AssignRoleRequest(@NotNull UUID roleId) {
    }

    public record RoleResponse(UUID id, String name, String description, List<String> permissions) {
    }

    public record PermissionResponse(String code, String description) {
    }

    /** Kết quả auth-service dùng để nhúng roles/permissions vào JWT. */
    public record UserAccessResponse(UUID userId, List<String> roles, List<String> permissions) {
    }
}

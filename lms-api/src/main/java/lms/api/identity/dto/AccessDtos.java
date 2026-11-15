package lms.api.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Hợp đồng FE cho role, permission và gán role. */
public final class AccessDtos {

    private AccessDtos() {
    }

    public record CreateRoleRequest(@NotBlank String name, @NotBlank String description, Boolean isAdmin) {
    }

    /** Chỉ đổi tên/mô tả; cờ isAdmin không đổi được. */
    public record UpdateRoleRequest(@NotBlank String name, @NotBlank String description) {
    }

    public record CreatePermissionRequest(@NotBlank String code, @NotBlank String description) {
    }

    public record GrantPermissionRequest(@NotBlank String permissionCode) {
    }

    public record AssignRoleRequest(@NotNull UUID roleId) {
    }

    public record RoleView(UUID id, String name, String description, boolean isAdmin, List<String> permissions) {
    }

    public record PermissionView(String code, String description, String source, String service, String httpMethod,
                                 String pathPattern, Instant firstSeenAt) {
    }

    public record UserAccessView(UUID userId, List<String> roles, List<String> permissions, boolean admin) {
    }
}

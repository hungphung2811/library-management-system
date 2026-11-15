package lms.identity.accesscontrol.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request/response của REST API. */
public final class AccessDtos {

    private AccessDtos() {
    }

    /** isAdmin: tích chọn khi tạo role để đánh dấu đây là role quản trị toàn quyền; bỏ trống nghĩa là false. */
    public record CreateRoleRequest(@NotBlank String name, @NotBlank String description, Boolean isAdmin) {

        public CreateRoleRequest(String name, String description) {
            this(name, description, false);
        }
    }

    public record UpdateRoleRequest(@NotBlank String name, @NotBlank String description) {
    }

    public record CreatePermissionRequest(@NotBlank String code, @NotBlank String description) {
    }

    public record GrantPermissionRequest(@NotBlank String permissionCode) {
    }

    public record AssignRoleRequest(@NotNull UUID roleId) {
    }

    public record RoleResponse(UUID id, String name, String description, boolean isAdmin, List<String> permissions) {

        public RoleResponse(UUID id, String name, String description, List<String> permissions) {
            this(id, name, description, false, permissions);
        }
    }

    /**
     * source: AUTO (khóa tự sinh theo endpoint) hoặc CUSTOM (mã BE đặt). service, httpMethod, pathPattern,
     * firstSeenAt cho biết khóa gắn với endpoint nào; rỗng với permission tạo thủ công chưa có endpoint.
     */
    public record PermissionResponse(String code, String description, String source, String service,
                                     String httpMethod, String pathPattern, Instant firstSeenAt) {

        public PermissionResponse(String code, String description) {
            this(code, description, "CUSTOM", null, null, null, null);
        }
    }

    /** Kết quả auth-service dùng để nhúng roles, permissions và cờ admin vào JWT. */
    public record UserAccessResponse(UUID userId, List<String> roles, List<String> permissions, boolean admin) {

        public UserAccessResponse(UUID userId, List<String> roles, List<String> permissions) {
            this(userId, roles, permissions, false);
        }
    }
}

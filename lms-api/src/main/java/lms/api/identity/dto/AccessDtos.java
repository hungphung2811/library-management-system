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

    /**
     * Đổi tên/mô tả và (tùy chọn) cờ signupDefault; cờ isAdmin không đổi được. signupDefault null = giữ nguyên; bật cờ
     * chỉ quản trị viên được (identity trả 403), role quản trị không mang cờ (400), tenant đã có role đăng ký khác thì 409.
     */
    public record UpdateRoleRequest(@NotBlank String name, @NotBlank String description, Boolean signupDefault) {

        public UpdateRoleRequest(String name, String description) {
            this(name, description, null);
        }
    }

    public record CreatePermissionRequest(@NotBlank String code, @NotBlank String description) {
    }

    public record GrantPermissionRequest(@NotBlank String permissionCode) {
    }

    public record AssignRoleRequest(@NotNull UUID roleId) {
    }

    /** tenantId: tenant của dòng (ADR-0008); null khi service chưa trả. */
    public record RoleView(UUID id, String name, String description, boolean isAdmin, List<String> permissions,
                           UUID tenantId, boolean signupDefault) {

        public RoleView(UUID id, String name, String description, boolean isAdmin, List<String> permissions,
                        UUID tenantId) {
            this(id, name, description, isAdmin, permissions, tenantId, false);
        }
    }

    public record PermissionView(String code, String description, String source, String service, String httpMethod,
                                 String pathPattern, Instant firstSeenAt) {
    }

    public record UserAccessView(UUID userId, List<String> roles, List<String> permissions, boolean admin) {
    }
}

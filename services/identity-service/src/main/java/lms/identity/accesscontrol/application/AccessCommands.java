package lms.identity.accesscontrol.application;

import java.time.Instant;
import java.util.UUID;

/** Gom các command đầu vào của use case. */
public final class AccessCommands {

    private AccessCommands() {
    }

    /** requestedByAdmin: người gọi có phải quản trị viên không; chỉ quản trị viên mới tạo được role quản trị. */
    public record CreateRole(String name, String description, boolean admin, boolean requestedByAdmin) {

        public CreateRole(String name, String description) {
            this(name, description, false, false);
        }
    }

    public record CreatePermission(String code, String description) {
    }

    public record GrantPermission(UUID roleId, String permissionCode) {
    }

    /** callerIsAdmin: chỉ quản trị viên mới gán hoặc thu hồi được role quản trị (chống leo thang đặc quyền). */
    public record UserRole(UUID userId, UUID roleId, boolean callerIsAdmin) {

        public UserRole(UUID userId, UUID roleId) {
            this(userId, roleId, false);
        }
    }

    /** Khóa quyền do một endpoint của một service báo về khi được gọi lần đầu. */
    public record RegisterPermissionKey(String service, String key, String source, String httpMethod,
                                        String pathPattern, String description, Instant seenAt) {
    }
}

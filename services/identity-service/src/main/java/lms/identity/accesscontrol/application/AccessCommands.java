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

    /**
     * Đổi tên/mô tả (và cờ signupDefault khi không null); role quản trị (isAdmin) chỉ quản trị viên được sửa, bật cờ
     * signupDefault cũng chỉ quản trị viên (role đó sẽ cấp cho mọi tài khoản tự đăng ký).
     */
    public record UpdateRole(UUID roleId, String name, String description, boolean requestedByAdmin,
                             Boolean signupDefault) {

        public UpdateRole(UUID roleId, String name, String description, boolean requestedByAdmin) {
            this(roleId, name, description, requestedByAdmin, null);
        }
    }

    public record DeleteRole(UUID roleId, boolean requestedByAdmin) {
    }

    public record CreatePermission(String code, String description) {
    }

    /**
     * requestedByAdmin: khóa thêm vào role signup_default (cấp cho mọi tài khoản tự đăng ký công khai) chỉ quản trị
     * viên được thêm; người chỉ có ROLE_GRANT không admin bị 403 (chống leo thang đặc quyền qua đăng ký).
     */
    public record GrantPermission(UUID roleId, String permissionCode, boolean requestedByAdmin) {

        public GrantPermission(UUID roleId, String permissionCode) {
            this(roleId, permissionCode, false);
        }
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

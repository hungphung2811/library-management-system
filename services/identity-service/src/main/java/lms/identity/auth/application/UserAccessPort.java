package lms.identity.auth.application;

import java.util.List;
import java.util.UUID;

/**
 * Lấy role và permission hiệu lực của người dùng từ context accesscontrol, trong tenant đang ở TenantContext (role là
 * dữ liệu thuộc tenant; không có tenant thì không có role nào). Người gọi đặt tenant bằng TenantContext.callAs.
 */
public interface UserAccessPort {

    UserAccess fetch(UUID userId);

    /** admin: người dùng có ít nhất một role đánh dấu is_admin (bỏ qua kiểm tra permission theo endpoint). */
    record UserAccess(UUID userId, List<String> roles, List<String> permissions, boolean admin) {

        public UserAccess(UUID userId, List<String> roles, List<String> permissions) {
            this(userId, roles, permissions, false);
        }
    }
}

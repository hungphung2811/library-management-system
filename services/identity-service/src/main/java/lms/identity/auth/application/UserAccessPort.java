package lms.identity.auth.application;

import java.util.List;
import java.util.UUID;

/**
 * Lấy role và permission hiệu lực của người dùng từ context accesscontrol, trong tenant đang ở TenantContext (role là
 * dữ liệu thuộc tenant; không có tenant thì không có role nào).
 *
 * <p>Bất biến với người gọi: (1) người gọi đặt tenant bằng {@code TenantContext.callAs(tenant, ...)} trước khi gọi;
 * (2) KHÔNG gọi bên trong một transaction đang mở: tenant của phiên Hibernate và biến RLS {@code app.tenant_id} được
 * chốt khi transaction bắt đầu, và gọi lồng sẽ giữ 2 kết nối cùng lúc (R20).
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

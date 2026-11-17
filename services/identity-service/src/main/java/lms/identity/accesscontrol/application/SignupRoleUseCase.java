package lms.identity.accesscontrol.application;

import java.util.Optional;
import java.util.UUID;
import lms.identity.accesscontrol.application.AccessCommands.UserRole;
import lms.identity.accesscontrol.domain.AccessControlNotFoundException;
import lms.identity.accesscontrol.domain.Role;
import lms.identity.accesscontrol.domain.RoleRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Role tự đăng ký của tenant hiện tại (ADR-0010 B2.1): role mang cờ signup_default. Dùng cho luồng tự đăng ký công
 * khai, nên role quản trị (isAdmin) KHÔNG BAO GIỜ được trả/gán ở đây, kể cả khi dữ liệu bị sửa tay (cờ + admin bị
 * CHECK ở DB cấm, kiểm lại ở đây như hàng rào thứ hai). Chạy trong TenantContext của người gọi (roles chịu RLS).
 */
public class SignupRoleUseCase {

    private final RoleRepository roles;
    private final UserRoleUseCase userRoles;

    public SignupRoleUseCase(RoleRepository roles, UserRoleUseCase userRoles) {
        this.roles = roles;
        this.userRoles = userRoles;
    }

    /** Id role đăng ký mặc định của tenant hiện tại; rỗng nếu tenant chưa có (hoặc dữ liệu không hợp lệ: là admin). */
    @Transactional(readOnly = true)
    public Optional<UUID> find() {
        return roles.findAll().stream()
                .filter(Role::isSignupDefault)
                .filter(r -> !r.isAdmin())
                .map(r -> r.id().value())
                .findFirst();
    }

    /** Gán role đăng ký mặc định cho người dùng (người gọi không phải admin nên không bao giờ gán được role admin). */
    @Transactional
    public void assignTo(UUID userId) {
        UUID roleId = find().orElseThrow(() -> new AccessControlNotFoundException("Tenant chưa có role đăng ký mặc định"));
        userRoles.assign(new UserRole(userId, roleId, false));
    }
}

package lms.identity.user.application;

import lms.identity.user.domain.UserCriteria;
import lms.identity.user.domain.UserPage;
import lms.identity.user.domain.UserRepository;
import lms.identity.user.domain.UserStatus;
import java.util.List;
import java.util.UUID;
import lms.tenant.TenantContext;

/**
 * Xuất danh sách người dùng của tenant hiện tại (hoặc mọi tenant ở phạm vi ALL của platform admin, như tìm kiếm) ra CSV
 * theo cùng bộ lọc tìm kiếm, tối đa {@value #MAX_ROWS} dòng đầu theo thứ tự tên đăng nhập. Cột phòng ban là MÃ phòng ban
 * (khớp departmentCode của nhập); roles là tên role phân tách bằng dấu chấm phẩy (role của tenant hiện tại).
 */
public class ExportUsersUseCase {

    public static final int MAX_ROWS = 5000;

    private final UserRepository repository;
    private final DepartmentLookupPort departments;
    private final UserRoleLookupPort roles;

    public ExportUsersUseCase(UserRepository repository, DepartmentLookupPort departments, UserRoleLookupPort roles) {
        this.repository = repository;
        this.departments = departments;
        this.roles = roles;
    }

    /** @return nội dung CSV (chưa có BOM) */
    public String execute(String keyword, UUID departmentId, UserStatus status) {
        UUID tenantId = TenantContext.readFilter().orElse(null);
        String k = keyword == null || keyword.isBlank() ? null : keyword.trim();
        UserPage page = repository.search(new UserCriteria(tenantId, k, departmentId, status, 0, MAX_ROWS));
        List<UserResponse> users = page.items().stream().map(UserResponse::from).toList();
        if (tenantId == null) { // phạm vi nhiều tenant: phòng ban/role là dữ liệu của từng tenant, không tra gộp
            return UsersCsv.render(users, java.util.Map.of(), java.util.Map.of());
        }
        return UsersCsv.render(users, departments.codesById(),
                roles.roleNames(users.stream().map(UserResponse::id).distinct().toList()));
    }
}

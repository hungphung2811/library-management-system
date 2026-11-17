package lms.identity.user.application;

import lms.identity.user.domain.UserCriteria;
import lms.identity.user.domain.UserPage;
import lms.identity.user.domain.UserRepository;
import lms.identity.user.domain.UserStatus;
import java.util.List;
import java.util.UUID;
import lms.tenant.TenantContext;

/** Liệt kê/tìm người dùng của tenant hiện tại (có membership ở tenant) hoặc của mọi tenant ở phạm vi ALL; departmentId là phòng ban trong tenant. */
public class SearchUsersUseCase {

    static final int MAX_PAGE_SIZE = 100;

    private final UserRepository repository;
    private final UserLockStatus lockStatus;

    public SearchUsersUseCase(UserRepository repository) {
        this(repository, null);
    }

    /** lockStatus null: không điền trạng thái khóa (locked=false). */
    public SearchUsersUseCase(UserRepository repository, UserLockStatus lockStatus) {
        this.repository = repository;
        this.lockStatus = lockStatus;
    }

    public UserPageResponse execute(String keyword, UUID departmentId, UserStatus status, int page, int size) {
        // Một tenant: lọc theo tenant; ALL (platform admin, ADR-0008): null = mọi tenant, mỗi dòng là cặp (user, tenant).
        UUID tenantId = TenantContext.readFilter().orElse(null);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        String k = keyword == null || keyword.isBlank() ? null : keyword.trim();
        UserPage result = repository.search(new UserCriteria(tenantId, k, departmentId, status, safePage, safeSize));
        List<UserResponse> items = result.items().stream().map(UserResponse::from).toList();
        return new UserPageResponse(lockStatus == null ? items : lockStatus.applyAll(items), result.total(),
                result.page(), result.size());
    }
}

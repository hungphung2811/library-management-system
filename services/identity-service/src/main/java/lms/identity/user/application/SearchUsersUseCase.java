package lms.identity.user.application;

import lms.identity.user.domain.UserCriteria;
import lms.identity.user.domain.UserPage;
import lms.identity.user.domain.UserRepository;
import lms.identity.user.domain.UserStatus;
import java.util.UUID;
import lms.tenant.TenantContext;

/** Liệt kê/tìm người dùng của tenant hiện tại (có membership ở tenant) hoặc của mọi tenant ở phạm vi ALL; departmentId là phòng ban trong tenant. */
public class SearchUsersUseCase {

    static final int MAX_PAGE_SIZE = 100;

    private final UserRepository repository;

    public SearchUsersUseCase(UserRepository repository) {
        this.repository = repository;
    }

    public UserPageResponse execute(String keyword, UUID departmentId, UserStatus status, int page, int size) {
        // Một tenant: lọc theo tenant; ALL (platform admin, ADR-0008): null = mọi tenant, mỗi dòng là cặp (user, tenant).
        UUID tenantId = TenantContext.readFilter().orElse(null);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        String k = keyword == null || keyword.isBlank() ? null : keyword.trim();
        UserPage result = repository.search(new UserCriteria(tenantId, k, departmentId, status, safePage, safeSize));
        return new UserPageResponse(result.items().stream().map(UserResponse::from).toList(), result.total(),
                result.page(), result.size());
    }
}

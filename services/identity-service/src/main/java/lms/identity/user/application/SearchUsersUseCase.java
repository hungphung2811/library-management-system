package lms.identity.user.application;

import lms.identity.user.domain.UserCriteria;
import lms.identity.user.domain.UserPage;
import lms.identity.user.domain.UserRepository;
import lms.identity.user.domain.UserStatus;
import java.util.UUID;

public class SearchUsersUseCase {

    static final int MAX_PAGE_SIZE = 100;

    private final UserRepository repository;

    public SearchUsersUseCase(UserRepository repository) {
        this.repository = repository;
    }

    public UserPageResponse execute(String keyword, UUID departmentId, UserStatus status, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        String k = keyword == null || keyword.isBlank() ? null : keyword.trim();
        UserPage result = repository.search(new UserCriteria(k, departmentId, status, safePage, safeSize));
        return new UserPageResponse(result.items().stream().map(UserResponse::from).toList(), result.total(),
                result.page(), result.size());
    }
}

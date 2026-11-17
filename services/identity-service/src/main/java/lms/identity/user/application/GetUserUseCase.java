package lms.identity.user.application;

import lms.identity.user.domain.UserNotFoundException;
import lms.identity.user.domain.UserRepository;
import java.util.Optional;
import java.util.UUID;
import lms.tenant.TenantContext;

/** Xem người dùng trong tenant hiện tại; người chỉ thuộc tenant khác: 404 (không lộ là có tồn tại). */
public class GetUserUseCase {

    private final UserRepository repository;
    private final UserLockStatus lockStatus;

    public GetUserUseCase(UserRepository repository) {
        this(repository, null);
    }

    /** lockStatus null: không điền trạng thái khóa (locked=false). */
    public GetUserUseCase(UserRepository repository, UserLockStatus lockStatus) {
        this.repository = repository;
        this.lockStatus = lockStatus;
    }

    private UserResponse withLock(UserResponse r) {
        return lockStatus == null ? r : lockStatus.apply(r);
    }

    public UserResponse execute(UUID id) {
        UUID tenantId = TenantContext.require();
        return repository.findInTenant(id, tenantId).map(UserResponse::from).map(this::withLock)
                .orElseThrow(() -> new UserNotFoundException(id));
    }

    /** Hồ sơ của chính mình; token không có tenant (phiên nền tảng) thì trả hồ sơ toàn cục, không có phòng ban. */
    public UserResponse me(UUID userId) {
        Optional<UUID> tenantId = TenantContext.current();
        if (tenantId.isPresent()) {
            return repository.findInTenant(userId, tenantId.get()).map(UserResponse::from).map(this::withLock)
                    .orElseThrow(() -> new UserNotFoundException(userId));
        }
        return repository.findById(userId).map(u -> UserResponse.from(u, null))
                .orElseThrow(() -> new UserNotFoundException(userId));
    }
}

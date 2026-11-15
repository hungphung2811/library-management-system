package lms.identity.user.application;

import lms.identity.user.domain.InvalidUserException;
import lms.identity.user.domain.TenantUser;
import lms.identity.user.domain.User;
import lms.identity.user.domain.UserOperationNotAllowedException;
import lms.identity.user.domain.UserRepository;
import lms.identity.user.domain.UserStatus;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vô hiệu hóa/kích hoạt lại người dùng. Vô hiệu hóa chặn đăng nhập và làm mới token ngay (access token đã phát còn hiệu lực
 * tới khi hết hạn). Không cho người dùng tự vô hiệu hóa chính mình để tránh tự khóa tài khoản quản trị.
 *
 * <p>Trạng thái tài khoản là toàn cục (ảnh hưởng mọi tenant) nên chỉ đổi được với tài khoản chỉ thuộc tenant hiện tại và
 * không phải platform admin ({@link UserOperationNotAllowedException}); người không thuộc tenant: 404.
 */
public class ChangeUserStatusUseCase {

    private final UserRepository repository;
    private final UserAccountGuard guard;

    public ChangeUserStatusUseCase(UserRepository repository, AccountCredentialPort credentials) {
        this.repository = repository;
        this.guard = new UserAccountGuard(repository, credentials);
    }

    @Transactional
    public UserResponse execute(UUID actorId, UUID id, UserStatus target) {
        UUID tenantId = TenantContext.require();
        TenantUser current = guard.requireInTenant(id, tenantId);
        User user = current.user();
        if (target == UserStatus.DISABLED) {
            if (id.equals(actorId)) {
                throw new InvalidUserException("Không thể tự vô hiệu hóa tài khoản của chính mình");
            }
            guard.requireAccountManageable(id);
            user.disable();
        } else {
            if (!id.equals(actorId)) guard.requireAccountManageable(id);
            user.enable();
        }
        return UserResponse.from(repository.save(user), current.departmentId());
    }
}

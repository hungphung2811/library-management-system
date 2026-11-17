package lms.identity.user.application;

import lms.identity.user.domain.InvalidUserException;
import lms.identity.user.domain.TenantUser;
import lms.identity.user.domain.UserOperationNotAllowedException;
import lms.identity.user.domain.UserRepository;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quản trị viên tenant mở khóa tài khoản bị khóa do đăng nhập sai nhiều lần (xóa failedAttempts và lockedUntil; phát
 * AccountUnlockedEvent cho audit). Khóa là trạng thái toàn cục của tài khoản nên áp cùng quy tắc với đặt lại mật khẩu:
 * chỉ tài khoản chỉ thuộc tenant hiện tại và không phải platform admin ({@link UserOperationNotAllowedException}, 403);
 * người không thuộc tenant: 404. Không đổi trạng thái vô hiệu hóa (DISABLED) của hồ sơ.
 */
public class UnlockUserUseCase {

    private final AccountCredentialPort credentials;
    private final UserAccountGuard guard;

    public UnlockUserUseCase(UserRepository repository, AccountCredentialPort credentials) {
        this.credentials = credentials;
        this.guard = new UserAccountGuard(repository, credentials);
    }

    @Transactional
    public UserResponse execute(UUID actorId, UUID userId) {
        UUID tenantId = TenantContext.require();
        TenantUser target = guard.requireInTenant(userId, tenantId);
        guard.requireAccountManageable(userId);
        if (!credentials.unlock(userId, actorId)) {
            throw new InvalidUserException("Người dùng chưa có tài khoản đăng nhập");
        }
        // Sau khi mở khóa: locked=false, lockedUntil=null (không cần tra lại)
        return UserResponse.from(target).withLock(null);
    }
}

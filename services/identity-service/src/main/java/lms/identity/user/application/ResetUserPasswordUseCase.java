package lms.identity.user.application;

import lms.identity.user.domain.InvalidUserException;
import lms.identity.user.domain.UserOperationNotAllowedException;
import lms.identity.user.domain.UserRepository;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quản trị viên tenant đặt lại mật khẩu (mật khẩu tạm, buộc đổi ở lần đăng nhập sau, thu hồi mọi phiên). Chỉ với tài
 * khoản chỉ thuộc đúng tenant hiện tại và không phải platform admin; ngược lại {@link UserOperationNotAllowedException}
 * (403). Người không thuộc tenant: 404.
 */
public class ResetUserPasswordUseCase {

    private final AccountCredentialPort credentials;
    private final UserAccountGuard guard;

    public ResetUserPasswordUseCase(UserRepository repository, AccountCredentialPort credentials) {
        this.credentials = credentials;
        this.guard = new UserAccountGuard(repository, credentials);
    }

    @Transactional
    public void execute(UUID userId, String temporaryPassword) {
        UUID tenantId = TenantContext.require();
        guard.requireMember(userId, tenantId);
        guard.requireAccountManageable(userId);
        if (temporaryPassword == null || temporaryPassword.length() < AccountCredentialPort.MIN_PASSWORD_LENGTH) {
            throw new InvalidUserException("Mật khẩu tạm phải có ít nhất " + AccountCredentialPort.MIN_PASSWORD_LENGTH
                    + " ký tự");
        }
        if (!credentials.resetToTemporaryPassword(userId, temporaryPassword)) {
            throw new InvalidUserException("Người dùng chưa có tài khoản đăng nhập");
        }
    }
}

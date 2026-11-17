package lms.identity.user.infrastructure.client;

import java.util.UUID;
import lms.identity.tenant.application.NewAdmin;
import lms.identity.tenant.application.PlatformAccountException;
import lms.identity.tenant.application.TenantAccountPort;
import lms.identity.user.application.AccountCredentialPort;
import lms.identity.user.application.CreateUserRequest;
import lms.identity.user.application.CreateUserUseCase;
import lms.identity.user.domain.DuplicateUserException;
import lms.identity.user.domain.InvalidUserException;

/**
 * Cung cấp {@link TenantAccountPort} cho context tenant (thao tác nền tảng): tạo tài khoản qua CreateUserUseCase (hồ sơ
 * users + credential mật khẩu tạm + membership vào tenant hiện tại trong TenantContext), dịch lỗi của context user sang
 * {@link PlatformAccountException}. Lỗi trùng vẫn là thông điệp chung (không lộ tài khoản thuộc tenant nào).
 */
public class PlatformTenantAccountAdapter implements TenantAccountPort {

    private final CreateUserUseCase createUser;
    private final AccountCredentialPort credentials;

    public PlatformTenantAccountAdapter(CreateUserUseCase createUser, AccountCredentialPort credentials) {
        this.createUser = createUser;
        this.credentials = credentials;
    }

    @Override
    public UUID createAccount(NewAdmin account) {
        return createAccount(account, null, true);
    }

    @Override
    public UUID createAccount(NewAdmin account, UUID userId, boolean mustChangePassword) {
        try {
            return createUser.execute(new CreateUserRequest(userId, account.username(), account.fullName(),
                    account.email(), null, account.temporaryPassword(), mustChangePassword)).id();
        } catch (DuplicateUserException e) {
            throw new PlatformAccountException(PlatformAccountException.Reason.DUPLICATE, e.getMessage());
        } catch (InvalidUserException e) {
            throw new PlatformAccountException(PlatformAccountException.Reason.INVALID, e.getMessage());
        }
    }

    @Override
    public boolean exists(UUID userId) {
        return userId != null && credentials.exists(userId);
    }
}

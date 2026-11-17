package lms.identity.user.application;

import java.util.UUID;
import lms.identity.user.domain.DuplicateUserException;
import lms.identity.user.domain.InvalidUserException;

/**
 * Tự đăng ký: tạo hồ sơ + credential + membership cho tenant hiện tại (TenantContext do người gọi đặt) bằng
 * {@link CreateUserUseCase}. Không nhận phòng ban, id, cờ đổi mật khẩu: id sinh mới, không phòng ban, không buộc đổi mật
 * khẩu. Không có @Transactional riêng: chạy trong transaction của người gọi (nó cũng phải gán role trong cùng
 * transaction); ngoại lệ domain được dịch sang {@link SelfRegistrationRejectedException} (tầng application).
 */
public class SelfRegisterUserUseCase {

    private final CreateUserUseCase create;

    public SelfRegisterUserUseCase(CreateUserUseCase create) {
        this.create = create;
    }

    public UUID execute(String username, String rawPassword, String fullName, String email) {
        try {
            return create.execute(new CreateUserRequest(null, username, fullName, email, null, rawPassword, false)).id();
        } catch (DuplicateUserException e) {
            throw new SelfRegistrationRejectedException(SelfRegistrationRejectedException.Reason.DUPLICATE,
                    DuplicateUserException.GENERIC_MESSAGE);
        } catch (InvalidUserException e) {
            throw new SelfRegistrationRejectedException(SelfRegistrationRejectedException.Reason.INVALID, e.getMessage());
        }
    }
}

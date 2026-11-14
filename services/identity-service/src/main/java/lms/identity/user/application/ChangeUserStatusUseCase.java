package lms.identity.user.application;

import lms.identity.user.domain.InvalidUserException;
import lms.identity.user.domain.User;
import lms.identity.user.domain.UserNotFoundException;
import lms.identity.user.domain.UserRepository;
import lms.identity.user.domain.UserStatus;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vô hiệu hóa/kích hoạt lại người dùng. Vô hiệu hóa chặn đăng nhập và làm mới token ngay (access token đã phát còn hiệu lực
 * tới khi hết hạn). Không cho người dùng tự vô hiệu hóa chính mình để tránh tự khóa tài khoản quản trị.
 */
public class ChangeUserStatusUseCase {

    private final UserRepository repository;

    public ChangeUserStatusUseCase(UserRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public UserResponse execute(UUID actorId, UUID id, UserStatus target) {
        User user = repository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
        if (target == UserStatus.DISABLED) {
            if (id.equals(actorId)) {
                throw new InvalidUserException("Không thể tự vô hiệu hóa tài khoản của chính mình");
            }
            user.disable();
        } else {
            user.enable();
        }
        return UserResponse.from(repository.save(user));
    }
}

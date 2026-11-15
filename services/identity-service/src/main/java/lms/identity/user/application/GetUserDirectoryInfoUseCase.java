package lms.identity.user.application;

import lms.identity.user.domain.UserRepository;
import java.util.Optional;
import java.util.UUID;

/** API của context user cho context auth khi cấp token. Không có bản ghi user (như tài khoản quản trị khởi tạo) thì rỗng. */
public class GetUserDirectoryInfoUseCase {

    private final UserRepository repository;

    public GetUserDirectoryInfoUseCase(UserRepository repository) {
        this.repository = repository;
    }

    public Optional<UserDirectoryInfo> execute(UUID userId) {
        return repository.findById(userId).map(u -> new UserDirectoryInfo(u.isDisabled()));
    }
}

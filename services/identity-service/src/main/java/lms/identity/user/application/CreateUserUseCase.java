package lms.identity.user.application;

import lms.identity.user.domain.DuplicateUserException;
import lms.identity.user.domain.InvalidUserException;
import lms.identity.user.domain.User;
import lms.identity.user.domain.UserRepository;
import org.springframework.transaction.annotation.Transactional;

public class CreateUserUseCase {

    private final UserRepository repository;
    private final DepartmentLookupPort departments;

    public CreateUserUseCase(UserRepository repository, DepartmentLookupPort departments) {
        this.repository = repository;
        this.departments = departments;
    }

    @Transactional
    public UserResponse execute(CreateUserRequest request) {
        User user = User.create(request.id(), request.username(), request.fullName(), request.email(), request.departmentId());
        if (user.departmentId() != null && !departments.existsActive(user.departmentId())) {
            throw new InvalidUserException("Phòng ban không tồn tại hoặc đã ngừng hoạt động");
        }
        if (repository.existsById(user.id())) {
            throw new DuplicateUserException("Người dùng đã tồn tại: " + user.id());
        }
        if (repository.existsByUsername(user.username())) {
            throw new DuplicateUserException("Tên đăng nhập đã tồn tại: " + user.username());
        }
        if (repository.existsByEmail(user.email(), null)) {
            throw new DuplicateUserException("Email đã được dùng: " + user.email());
        }
        return UserResponse.from(repository.save(user));
    }
}

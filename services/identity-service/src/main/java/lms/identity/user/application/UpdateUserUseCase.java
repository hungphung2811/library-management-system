package lms.identity.user.application;

import lms.identity.user.domain.DuplicateUserException;
import lms.identity.user.domain.InvalidUserException;
import lms.identity.user.domain.User;
import lms.identity.user.domain.UserNotFoundException;
import lms.identity.user.domain.UserRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class UpdateUserUseCase {

    private final UserRepository repository;
    private final DepartmentLookupPort departments;

    public UpdateUserUseCase(UserRepository repository, DepartmentLookupPort departments) {
        this.repository = repository;
        this.departments = departments;
    }

    @Transactional
    public UserResponse execute(UUID id, UpdateUserRequest request) {
        User user = repository.findById(id).orElseThrow(() -> new UserNotFoundException(id));
        UUID before = user.departmentId();
        user.update(request.fullName(), request.email(), request.departmentId());
        // Giữ nguyên phòng ban đã ngừng hoạt động thì không bị chặn; chỉ kiểm khi chuyển sang phòng ban khác
        if (user.departmentId() != null && !Objects.equals(before, user.departmentId())
                && !departments.existsActive(user.departmentId())) {
            throw new InvalidUserException("Phòng ban không tồn tại hoặc đã ngừng hoạt động");
        }
        if (repository.existsByEmail(user.email(), id)) {
            throw new DuplicateUserException("Email đã được dùng: " + user.email());
        }
        return UserResponse.from(repository.save(user));
    }
}

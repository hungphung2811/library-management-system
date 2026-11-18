package lms.identity.user.application;

import lms.identity.user.domain.DuplicateUserException;
import lms.identity.user.domain.InvalidUserException;
import lms.identity.user.domain.User;
import lms.identity.user.domain.UserRepository;
import java.util.Optional;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quản trị viên tenant tạo người dùng cho tenant hiện tại: hồ sơ users + (nếu có mật khẩu) credential + membership kèm
 * phòng ban, trong một transaction. Tên đăng nhập/email/id đã có ở BẤT KỲ đâu (mọi tenant) đều báo cùng một lỗi chung
 * {@link DuplicateUserException#generic()}: không lộ tài khoản thuộc tenant nào (spec 5.6, Review Focus 4).
 */
public class CreateUserUseCase {

    private final UserRepository repository;
    private final DepartmentLookupPort departments;
    private final AccountCredentialPort credentials;
    private final TenantMembershipPort memberships;

    public CreateUserUseCase(UserRepository repository, DepartmentLookupPort departments,
                             AccountCredentialPort credentials, TenantMembershipPort memberships) {
        this.repository = repository;
        this.departments = departments;
        this.credentials = credentials;
        this.memberships = memberships;
    }

    @Transactional
    public UserResponse execute(CreateUserRequest request) {
        UUID tenantId = TenantContext.require();
        User user = User.create(request.id(), request.username(), request.fullName(), request.email());
        user.mergePersonalInfo(request.personalInfo(java.time.LocalDate.now()));
        UUID departmentId = request.departmentId();
        // departments chịu RLS/@TenantId: phòng ban của tenant khác không thấy nên cũng bị từ chối ở đây
        if (departmentId != null && !departments.existsActive(departmentId)) {
            throw new InvalidUserException("Phòng ban không tồn tại hoặc đã ngừng hoạt động");
        }
        boolean withCredential = request.password() != null;
        if (withCredential && request.password().length() < AccountCredentialPort.MIN_PASSWORD_LENGTH) {
            throw new InvalidUserException("Mật khẩu phải có ít nhất " + AccountCredentialPort.MIN_PASSWORD_LENGTH + " ký tự");
        }
        ensureUnique(user, tenantId, withCredential);

        repository.save(user);
        if (withCredential) {
            credentials.register(user.id(), user.username(), request.password(),
                    Boolean.TRUE.equals(request.mustChangePassword()));
        }
        memberships.join(user.id(), tenantId, departmentId);
        return repository.findInTenant(user.id(), tenantId).map(UserResponse::from)
                .orElseGet(() -> UserResponse.from(user, departmentId));
    }

    private void ensureUnique(User user, UUID tenantId, boolean withCredential) {
        if (repository.existsById(user.id())
                || repository.existsByUsername(user.username())
                || repository.existsByEmail(user.email(), null)) {
            throw DuplicateUserException.generic();
        }
        Optional<UUID> usernameOwner = credentials.findUserIdByUsername(user.username());
        if (withCredential) {
            if (usernameOwner.isPresent() || credentials.exists(user.id())) throw DuplicateUserException.generic();
            return;
        }
        if (usernameOwner.isPresent() && !usernameOwner.get().equals(user.id())) throw DuplicateUserException.generic();
        // Gắn hồ sơ vào credential có sẵn: credential phải đã là thành viên tenant hiện tại; credential của tenant khác
        // báo trùng chung chung (gắn tài khoản có sẵn là việc của người vận hành)
        if (credentials.exists(user.id()) && !repository.hasMembership(user.id(), tenantId)) {
            throw DuplicateUserException.generic();
        }
    }
}

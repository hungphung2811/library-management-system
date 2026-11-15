package lms.identity.user.application;

import lms.identity.user.domain.DuplicateUserException;
import lms.identity.user.domain.InvalidUserException;
import lms.identity.user.domain.TenantUser;
import lms.identity.user.domain.User;
import lms.identity.user.domain.UserOperationNotAllowedException;
import lms.identity.user.domain.UserRepository;
import java.util.Objects;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sửa người dùng trong tenant hiện tại. Phòng ban (dữ liệu tenant, ghi vào membership) luôn đổi được cho thành viên.
 * Họ tên/email (toàn cục) chỉ đổi được khi tài khoản chỉ thuộc tenant này và không phải platform admin
 * ({@link UserOperationNotAllowedException}); chính người dùng (actorId = id) luôn được. Người không thuộc tenant: 404.
 */
public class UpdateUserUseCase {

    private final UserRepository repository;
    private final DepartmentLookupPort departments;
    private final TenantMembershipPort memberships;
    private final UserAccountGuard guard;

    public UpdateUserUseCase(UserRepository repository, DepartmentLookupPort departments,
                             AccountCredentialPort credentials, TenantMembershipPort memberships) {
        this.repository = repository;
        this.departments = departments;
        this.memberships = memberships;
        this.guard = new UserAccountGuard(repository, credentials);
    }

    @Transactional
    public UserResponse execute(UUID actorId, UUID id, UpdateUserRequest request) {
        UUID tenantId = TenantContext.require();
        TenantUser current = guard.requireInTenant(id, tenantId);
        User user = current.user();
        boolean profileChanged = user.profileDiffers(request.fullName(), request.email());
        if (profileChanged && !id.equals(actorId)) {
            guard.requireAccountManageable(id);
        }
        user.update(request.fullName(), request.email());

        UUID after = request.departmentId();
        boolean departmentChanged = !Objects.equals(current.departmentId(), after);
        // Giữ nguyên phòng ban đã ngừng hoạt động thì không bị chặn; chỉ kiểm khi chuyển sang phòng ban khác
        if (after != null && departmentChanged && !departments.existsActive(after)) {
            throw new InvalidUserException("Phòng ban không tồn tại hoặc đã ngừng hoạt động");
        }
        if (profileChanged) {
            if (repository.existsByEmail(user.email(), id)) throw DuplicateUserException.generic();
            repository.save(user);
        }
        if (departmentChanged) {
            memberships.changeDepartment(id, tenantId, after);
        }
        return UserResponse.from(user, after);
    }
}

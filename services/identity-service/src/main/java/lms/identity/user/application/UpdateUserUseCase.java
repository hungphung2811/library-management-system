package lms.identity.user.application;

import lms.identity.user.domain.DuplicateUserException;
import lms.identity.user.domain.InvalidUserException;
import lms.identity.user.domain.TenantUser;
import lms.identity.user.domain.User;
import lms.identity.user.domain.UserPersonalInfo;
import lms.identity.user.domain.UserOperationNotAllowedException;
import lms.identity.user.domain.UserRepository;
import java.time.LocalDate;
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
    private final UserLockStatus lockStatus;

    public UpdateUserUseCase(UserRepository repository, DepartmentLookupPort departments,
                             AccountCredentialPort credentials, TenantMembershipPort memberships) {
        this(repository, departments, credentials, memberships, null);
    }

    public UpdateUserUseCase(UserRepository repository, DepartmentLookupPort departments,
                             AccountCredentialPort credentials, TenantMembershipPort memberships,
                             UserLockStatus lockStatus) {
        this.lockStatus = lockStatus;
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
        boolean nameOrEmailChanged = user.profileDiffers(request.fullName(), request.email());
        UserPersonalInfo patch = request.personalInfo(LocalDate.now());
        boolean personalChanged = !user.personalInfo().merge(patch).equals(user.personalInfo());
        boolean profileChanged = nameOrEmailChanged || personalChanged;
        if (profileChanged && !id.equals(actorId)) {
            guard.requireAccountManageable(id);
        }
        user.update(request.fullName(), request.email());
        user.mergePersonalInfo(patch);

        UUID after = request.departmentId();
        boolean departmentChanged = !Objects.equals(current.departmentId(), after);
        // Giữ nguyên phòng ban đã ngừng hoạt động thì không bị chặn; chỉ kiểm khi chuyển sang phòng ban khác
        if (after != null && departmentChanged && !departments.existsActive(after)) {
            throw new InvalidUserException("Phòng ban không tồn tại hoặc đã ngừng hoạt động");
        }
        if (profileChanged) {
            if (nameOrEmailChanged && repository.existsByEmail(user.email(), id)) throw DuplicateUserException.generic();
            repository.save(user);
        }
        if (departmentChanged) {
            memberships.changeDepartment(id, tenantId, after);
        }
        UserResponse response = UserResponse.from(user, after);
        return lockStatus == null ? response : lockStatus.apply(response);
    }
}

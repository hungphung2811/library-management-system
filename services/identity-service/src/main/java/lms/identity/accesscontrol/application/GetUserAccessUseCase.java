package lms.identity.accesscontrol.application;

import lms.identity.accesscontrol.application.AccessDtos.UserAccessResponse;
import lms.identity.accesscontrol.application.AccessMapper;
import lms.identity.accesscontrol.domain.UserRoleAssignment;
import lms.identity.accesscontrol.domain.Role;
import lms.identity.accesscontrol.domain.RoleRepository;
import lms.identity.accesscontrol.domain.UserRoleRepository;
import lms.identity.accesscontrol.domain.EffectivePermissionsService;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tính role và permission hiệu lực của người dùng (Check User Permission) trong tenant hiện tại: role và gán role là
 * dữ liệu thuộc tenant (Hibernate @TenantId + RLS), nên chỉ role của tenant đang ở TenantContext được tính; không có
 * tenant thì không có role nào (fail-closed).
 *
 * <p>Luôn mở transaction mới (REQUIRES_NEW): tenant của phiên Hibernate và biến phiên RLS được chốt khi transaction
 * bắt đầu, nên người gọi đổi TenantContext (ví dụ cấp token cho tenant vừa chọn trong lúc đăng nhập, khi transaction
 * ngoài chưa có tenant) vẫn đọc đúng tenant.
 */
public class GetUserAccessUseCase {

    private final UserRoleRepository assignments;
    private final RoleRepository roles;
    private final EffectivePermissionsService permissionsService;

    public GetUserAccessUseCase(UserRoleRepository assignments, RoleRepository roles,
                                EffectivePermissionsService permissionsService) {
        this.assignments = assignments;
        this.roles = roles;
        this.permissionsService = permissionsService;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public UserAccessResponse execute(UUID userId) {
        List<Role> userRoles = assignments.findByUserId(userId)
                .map(UserRoleAssignment::roleIds)
                .map(roles::findAllByIds)
                .orElse(List.of());
        return new UserAccessResponse(userId,
                AccessMapper.sorted(userRoles.stream().map(Role::name).toList()),
                AccessMapper.sorted(permissionsService.resolve(userRoles)),
                permissionsService.isAdmin(userRoles));
    }
}

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
 * <p>Người gọi phải gọi ngoài mọi transaction đang mở (luồng cấp token tuân thủ từ R20), nên thực tế transaction này là
 * transaction duy nhất, mở sau khi tenant đã được đặt. REQUIRES_NEW chỉ là lưới an toàn: tenant của phiên Hibernate và
 * biến RLS được chốt khi transaction bắt đầu, nếu ai đó lỡ gọi lồng thì vẫn đọc đúng tenant (đổi lại giữ 2 kết nối),
 * thay vì âm thầm trả quyền rỗng.
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

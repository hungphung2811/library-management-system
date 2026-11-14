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
import org.springframework.transaction.annotation.Transactional;

/** Tính role và permission hiệu lực của người dùng (Check User Permission). */
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

    @Transactional(readOnly = true)
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

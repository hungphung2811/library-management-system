package lms.identity.accesscontrol.application;

import lms.identity.accesscontrol.application.AccessCommands.UserRole;
import lms.identity.accesscontrol.application.PublishAccessEventPort;
import lms.identity.accesscontrol.application.SaveUserRolePort;
import lms.identity.accesscontrol.domain.AccessControlForbiddenException;
import lms.identity.accesscontrol.domain.AccessControlNotFoundException;
import lms.identity.accesscontrol.domain.UserRoleAssignment;
import lms.identity.accesscontrol.domain.Role;
import lms.identity.accesscontrol.domain.RoleId;
import lms.identity.accesscontrol.domain.RoleRepository;
import lms.identity.accesscontrol.domain.UserRoleRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.transaction.annotation.Transactional;

/** Gán và thu hồi role của người dùng. Mỗi thay đổi phát event để Audit ghi nhận và Auth thu hồi phiên. */
public class UserRoleUseCase {

    private final RoleRepository roles;
    private final UserRoleRepository assignments;
    private final SaveUserRolePort savePort;
    private final PublishAccessEventPort eventPort;
    private final Clock clock;

    public UserRoleUseCase(RoleRepository roles, UserRoleRepository assignments, SaveUserRolePort savePort,
                           PublishAccessEventPort eventPort, Clock clock) {
        this.roles = roles;
        this.assignments = assignments;
        this.savePort = savePort;
        this.eventPort = eventPort;
        this.clock = clock;
    }

    @Transactional
    public void assign(UserRole command) {
        Role role = loadRole(command);
        requireAdminForAdminRole(role, command);
        UserRoleAssignment assignment = assignments.findByUserId(command.userId())
                .orElseGet(() -> UserRoleAssignment.empty(command.userId()));
        assignment.assign(role.id(), role.name(), Instant.now(clock));
        savePort.save(assignment);
        assignment.pullEvents().forEach(eventPort::publish);
    }

    @Transactional
    public void revoke(UserRole command) {
        Role role = loadRole(command);
        requireAdminForAdminRole(role, command);
        UserRoleAssignment assignment = assignments.findByUserId(command.userId())
                .orElseThrow(() -> new AccessControlNotFoundException("Người dùng chưa có role nào: " + command.userId()));
        assignment.revoke(role.id(), role.name(), Instant.now(clock));
        savePort.save(assignment);
        assignment.pullEvents().forEach(eventPort::publish);
    }

    /** Người chỉ có ROLE_ASSIGN không được tự nâng mình hay người khác lên quản trị, hay hạ quản trị viên. */
    private static void requireAdminForAdminRole(Role role, UserRole command) {
        if (role.isAdmin() && !command.callerIsAdmin()) {
            throw new AccessControlForbiddenException("Chỉ quản trị viên mới gán hoặc thu hồi được role quản trị");
        }
    }

    private Role loadRole(UserRole command) {
        return roles.findById(new RoleId(command.roleId()))
                .orElseThrow(() -> new AccessControlNotFoundException("Không tìm thấy role: " + command.roleId()));
    }
}

package lms.identity.user.application;

import lms.identity.user.domain.TenantUser;
import lms.identity.user.domain.User;
import lms.identity.user.domain.UserStatus;
import java.util.UUID;

/** departmentId: phòng ban trong tenant hiện tại (tenant_memberships.department_id). */
public record UserResponse(UUID id, String username, String fullName, String email, UUID departmentId, UserStatus status) {

    public static UserResponse from(TenantUser t) {
        return from(t.user(), t.departmentId());
    }

    public static UserResponse from(User u, UUID departmentId) {
        return new UserResponse(u.id(), u.username(), u.fullName(), u.email(), departmentId, u.status());
    }
}

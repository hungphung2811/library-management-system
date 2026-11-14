package lms.identity.user.application;

import lms.identity.user.domain.User;
import lms.identity.user.domain.UserStatus;
import java.util.UUID;

public record UserResponse(UUID id, String username, String fullName, String email, UUID departmentId, UserStatus status) {

    public static UserResponse from(User u) {
        return new UserResponse(u.id(), u.username(), u.fullName(), u.email(), u.departmentId(), u.status());
    }
}

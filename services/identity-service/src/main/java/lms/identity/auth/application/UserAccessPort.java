package lms.identity.auth.application;

import java.util.List;
import java.util.UUID;

/** Lấy role và permission hiệu lực của người dùng từ access-control-service. */
public interface UserAccessPort {

    UserAccess fetch(UUID userId);

    record UserAccess(UUID userId, List<String> roles, List<String> permissions) {
    }
}

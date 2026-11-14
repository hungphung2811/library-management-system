package lms.identity.accesscontrol.domain;

import lms.identity.accesscontrol.domain.UserRoleAssignment;
import java.util.Optional;
import java.util.UUID;

public interface UserRoleRepository {
    Optional<UserRoleAssignment> findByUserId(UUID userId);
}

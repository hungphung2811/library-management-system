package lms.identity.accesscontrol.application;

import lms.identity.accesscontrol.domain.UserRoleAssignment;

public interface SaveUserRolePort {
    UserRoleAssignment save(UserRoleAssignment assignment);
}

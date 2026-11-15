package lms.identity.accesscontrol.application;

import lms.identity.accesscontrol.domain.Role;

public interface SaveRolePort {
    Role save(Role role);

    void delete(Role role);
}

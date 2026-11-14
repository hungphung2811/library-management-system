package lms.identity.accesscontrol.application;

import lms.identity.accesscontrol.domain.Permission;

public interface SavePermissionPort {
    Permission save(Permission permission);
}

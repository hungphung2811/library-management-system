package lms.identity.accesscontrol.domain;

import lms.identity.accesscontrol.domain.Permission;
import java.util.List;

public interface PermissionRepository {
    boolean existsByCode(String code);

    List<Permission> findAllPermissions();
}

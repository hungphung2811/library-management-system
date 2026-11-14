package lms.identity.accesscontrol.application;

import lms.identity.accesscontrol.application.AccessDtos.PermissionResponse;
import lms.identity.accesscontrol.application.AccessDtos.RoleResponse;
import lms.identity.accesscontrol.domain.Permission;
import lms.identity.accesscontrol.domain.Role;
import java.util.TreeSet;

public final class AccessMapper {

    private AccessMapper() {
    }

    public static RoleResponse toResponse(Role role) {
        return new RoleResponse(role.id().value(), role.name(), role.description(), role.isAdmin(),
                role.permissionCodes().stream().sorted().toList());
    }

    public static PermissionResponse toResponse(Permission permission) {
        return new PermissionResponse(permission.code(), permission.description(), permission.source(),
                permission.service(), permission.httpMethod(), permission.pathPattern(), permission.firstSeenAt());
    }

    public static java.util.List<String> sorted(java.util.Collection<String> values) {
        return new TreeSet<>(values).stream().toList();
    }
}

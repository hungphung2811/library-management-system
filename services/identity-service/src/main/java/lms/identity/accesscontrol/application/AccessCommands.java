package lms.identity.accesscontrol.application;

import java.util.UUID;

/** Gom các command đầu vào của use case. */
public final class AccessCommands {

    private AccessCommands() {
    }

    public record CreateRole(String name, String description) {
    }

    public record CreatePermission(String code, String description) {
    }

    public record GrantPermission(UUID roleId, String permissionCode) {
    }

    public record UserRole(UUID userId, UUID roleId) {
    }
}

package lms.system.menu.domain;

import java.util.Set;

/** Người xem menu, lấy từ claim JWT ({@code admin}, {@code permissions}). */
public record MenuViewer(boolean admin, Set<String> permissions) {

    public MenuViewer {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public boolean canSee(String permissionKey) {
        return permissionKey == null || admin || permissions.contains(permissionKey);
    }
}

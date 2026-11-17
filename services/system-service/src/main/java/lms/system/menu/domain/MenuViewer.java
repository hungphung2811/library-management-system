package lms.system.menu.domain;

import java.util.Set;

/**
 * Người xem menu, lấy từ claim JWT ({@code admin}, {@code permissions}, {@code modules}). {@code modules} là tập
 * module tenant đang bật (đã áp quy tắc token cũ ở tầng web); mục có module không nằm trong tập này bị ẩn, kể cả
 * với admin.
 */
public record MenuViewer(boolean admin, Set<String> permissions, Set<String> modules) {

    public MenuViewer {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        modules = modules == null ? Set.of() : Set.copyOf(modules);
    }

    /** Không có module nào (chỉ thấy mục lõi). */
    public MenuViewer(boolean admin, Set<String> permissions) {
        this(admin, permissions, Set.of());
    }

    public boolean canSee(String permissionKey) {
        return permissionKey == null || admin || permissions.contains(permissionKey);
    }

    public boolean hasModule(String module) {
        return module == null || modules.contains(module);
    }
}

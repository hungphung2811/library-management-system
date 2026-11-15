package lms.system.menu.domain;

import java.util.regex.Pattern;

/** Kiểm định dạng các trường của menu. */
final class MenuRules {

    static final int CODE_MAX = 100;
    static final int LABEL_MAX = 200;
    static final int PATH_MAX = 500;
    static final int ICON_MAX = 100;
    static final int PERMISSION_KEY_MAX = 255;

    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]*$");
    /** {@code Controller/method} (ADR-0003) hoặc khóa tùy chỉnh gồm chữ, số, {@code _ . : -}. */
    private static final Pattern PERMISSION_KEY =
            Pattern.compile("^[A-Za-z0-9_.:-]+(/[A-Za-z0-9_.:-]+)?$");

    private MenuRules() {}

    static void validateCode(String code) {
        if (code == null || code.isBlank() || code.length() > CODE_MAX || !CODE.matcher(code).matches()) {
            throw new MenuException(MenuErrorCode.MENU_INVALID, "Mã menu không hợp lệ");
        }
    }

    static void validateLabel(String label) {
        if (label == null || label.isBlank() || label.length() > LABEL_MAX) {
            throw new MenuException(MenuErrorCode.MENU_INVALID, "Nhãn menu không hợp lệ");
        }
    }

    static void validateIcon(String icon) {
        if (icon != null && (icon.isBlank() || icon.length() > ICON_MAX)) {
            throw new MenuException(MenuErrorCode.MENU_INVALID, "Icon menu không hợp lệ");
        }
    }

    /**
     * Chỉ chấp nhận route nội bộ: bắt đầu bằng "/", không phải "//" (trình duyệt coi là URL cùng
     * giao thức tới host khác), không có ký tự điều khiển, khoảng trắng hay dấu gạch ngược.
     * Vì phải bắt đầu bằng "/" nên scheme như http: hay javascript: bị loại.
     */
    static void validatePath(String path) {
        if (path == null) {
            return;
        }
        if (path.length() > PATH_MAX || !path.startsWith("/") || path.startsWith("//")) {
            throw invalidPath();
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c <= 0x20 || c == 0x7f || c == 0x5c) {
                throw invalidPath();
            }
        }
    }

    static void validatePermissionKey(String key) {
        if (key == null) {
            return;
        }
        if (key.length() > PERMISSION_KEY_MAX || !PERMISSION_KEY.matcher(key).matches()) {
            throw new MenuException(MenuErrorCode.MENU_PERMISSION_KEY_INVALID, "Khóa quyền không hợp lệ");
        }
    }

    private static MenuException invalidPath() {
        return new MenuException(MenuErrorCode.MENU_PATH_INVALID, "Đường dẫn phải là route nội bộ bắt đầu bằng '/'");
    }
}

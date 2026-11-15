package lms.system.menu.domain;

/** Mã lỗi nghiệp vụ của menu (ADR-0007 mục 2.4). Ánh xạ sang HTTP do tầng web đảm nhiệm. */
public enum MenuErrorCode {
    MENU_NOT_FOUND,
    MENU_PARENT_NOT_FOUND,
    MENU_CODE_DUPLICATED,
    MENU_HAS_CHILDREN,
    MENU_VERSION_CONFLICT,
    MENU_CYCLE,
    MENU_TOO_DEEP,
    MENU_PATH_INVALID,
    MENU_PERMISSION_KEY_INVALID,
    MENU_LIMIT_EXCEEDED,
    /** Mã, nhãn, icon rỗng hoặc quá dài (lỗi dữ liệu đầu vào chung). */
    MENU_INVALID
}

package lms.security.permission;

/**
 * Module thương mại mà tenant có thể được bật (ADR-0009). Tập đóng, đổi bằng code; khác khóa quyền (là dữ liệu).
 * Tên enum trùng mã lưu ở {@code tenant_modules.module} và phần tử của claim JWT {@code modules}.
 */
public enum TenantModule {
    LENDING,
    SALES
}

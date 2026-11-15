package lms.security;

/** Tên claim trong access token do auth-service phát hành. */
public final class JwtClaims {

    private JwtClaims() {
    }

    public static final String USERNAME = "username";
    public static final String ROLES = "roles";
    public static final String PERMISSIONS = "permissions";
    public static final String DEPARTMENT_ID = "departmentId";
    /** True khi người dùng phải đổi mật khẩu trước: token loại này không mang permission nào. */
    public static final String MUST_CHANGE_PASSWORD = "mustChangePassword";
    /**
     * True khi người dùng có ít nhất một role đánh dấu is_admin (role quản trị toàn quyền). Dữ liệu này đến từ DB,
     * không gắn với tên role nào. Token của tài khoản phải đổi mật khẩu không bao giờ mang claim này.
     */
    public static final String ADMIN = "admin";
    /** Tenant mà token này thuộc về (UUID dạng chuỗi). Người vận hành nền tảng có thể không có claim này. */
    public static final String TENANT_ID = "tenantId";
    /** True khi người dùng là người vận hành nền tảng (platform admin); chỉ mở các endpoint {@code @PlatformAdminOnly}. */
    public static final String PLATFORM_ADMIN = "platformAdmin";
}

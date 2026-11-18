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
    /** Mảng mã module đang bật của tenant trong token (ví dụ {@code ["LENDING","SALES"]}, ADR-0009). Phiên nền tảng không có. */
    public static final String MODULES = "modules";
    /**
     * Có mặt chỉ ở token dịch vụ (không phải người dùng); giá trị là tên dịch vụ được cấp, ví dụ
     * {@link #SERVICE_CUSTOMER_API}. Token có claim này chỉ dùng được ở endpoint {@code @StorefrontAccessible} (ADR-0011).
     */
    public static final String SERVICE = "svc";
    /** Giá trị {@link #SERVICE} của token storefront do identity cấp cho customer-api. */
    public static final String SERVICE_CUSTOMER_API = "customer-api";
    /**
     * Claim chuẩn {@code aud} (mảng chuỗi) tách KÊNH token (customer-api-data-authorization 5.3). Token đăng nhập qua
     * customer-api mang {@link #AUDIENCE_STOREFRONT}: không admin/platformAdmin, bị lms-api từ chối, customer-api bắt
     * buộc. Token đăng nhập qua lms-api (nhân viên) không có {@code aud} trong giai đoạn chuyển tiếp, hoặc mang
     * {@link #AUDIENCE_STAFF} khi identity bật {@code lms.identity.token.staff-audience}.
     */
    public static final String AUDIENCE = "aud";
    /** aud của token kênh storefront (website khách do customer-api phục vụ). */
    public static final String AUDIENCE_STOREFRONT = "lms-storefront";
    /** aud của token kênh nhân viên (lms-api), chỉ ghi khi bật cờ cấu hình ở identity. */
    public static final String AUDIENCE_STAFF = "lms-staff";
    /**
     * Mã phiên (họ refresh token, UUID dạng chuỗi) của access token người dùng (ADR-0017): giữ nguyên qua refresh và đổi
     * tenant, đổi mỗi lần đăng nhập. Token dịch vụ ({@link #SERVICE}) và token phát trước ADR-0017 không có claim này.
     */
    public static final String SESSION_ID = "sid";
    /**
     * aud của token tích hợp máy-với-máy (client_credentials, thiết kế SAAS_API_AUTHENTICATION_DESIGN D3). Token loại này
     * chỉ dùng được ở endpoint {@code @IntegrationScope}, bị lms-api và customer-api từ chối, không bao giờ là admin.
     */
    public static final String AUDIENCE_INTEGRATION = "lms-integration";
    /** Các scope được cấp, chuỗi cách nhau bằng dấu cách (RFC 8693/9068), ví dụ {@code "books:read orders:read"}. */
    public static final String SCOPE = "scope";
    /** Định danh client của token tích hợp (RFC 9068); bằng {@code sub} và id application. */
    public static final String CLIENT_ID = "client_id";
    /** Id application (UUID dạng chuỗi) đã xin token tích hợp hoặc site key của token storefront. */
    public static final String APPLICATION_ID = "application_id";
}

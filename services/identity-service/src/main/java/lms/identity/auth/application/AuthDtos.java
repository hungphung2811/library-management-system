package lms.identity.auth.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import lms.identity.tenant.application.TenantSummary;

/** Request/response của REST API xác thực. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotNull UUID userId,
            @NotBlank @Size(min = 3, max = 100) String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            UUID departmentId,
            Boolean mustChangePassword) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 72) String newPassword) {
    }

    /** tenantCode: tùy chọn, mã tenant muốn vào (đường dẫn/QR của thư viện); bỏ trống thì chọn theo tenant dùng gần nhất. */
    public record LoginRequest(@NotBlank String username, @NotBlank String password, String tenantCode,
                               @Pattern(regexp = "STAFF|STOREFRONT", message = "channel phải là STAFF hoặc STOREFRONT")
                               String channel) {

        public LoginRequest(String username, String password, String tenantCode) {
            this(username, password, tenantCode, null);
        }

        public LoginRequest(String username, String password) {
            this(username, password, null, null);
        }
    }

    /**
     * channel: tùy chọn, kênh của người gọi (customer-api gửi STOREFRONT, lms-api không gửi = STAFF). Refresh token chỉ
     * làm mới được ở đúng kênh đã phát hành; lệch kênh thì 401.
     * tenantId: tùy chọn (ADR-0013 2.4, customer-api gửi tenant đã phân giải từ {code}). Có giá trị mà khác tenant của refresh
     * token (kể cả phiên nền tảng không có tenant) thì 401 như token không hợp lệ và token KHÔNG bị xoay vòng. Không gửi =
     * hành vi cũ. Chỉ refresh dùng; logout bỏ qua (thu hồi token nào cũng vô hại, idempotent).
     */
    public record RefreshRequest(@NotBlank String refreshToken,
                                 @Pattern(regexp = "STAFF|STOREFRONT", message = "channel phải là STAFF hoặc STOREFRONT")
                                 String channel,
                                 java.util.UUID tenantId) {

        public RefreshRequest(String refreshToken) {
            this(refreshToken, null, null);
        }

        public RefreshRequest(String refreshToken, String channel) {
            this(refreshToken, channel, null);
        }
    }

    /**
     * Tự đăng ký (ADR-0010 B2.1): allowlist trường. KHÔNG có role, flag, tenantId, trạng thái hay phòng ban: tài khoản
     * luôn nhận role signup_default của tenant {@code tenantCode} (mã công khai do customer-api lấy từ đường dẫn).
     * Trường JSON lạ bị bỏ qua. email bắt buộc vì hồ sơ người dùng yêu cầu email duy nhất.
     */
    public record SignupRequest(
            @NotBlank @Size(max = 50) String tenantCode,
            @NotBlank @Size(min = 3, max = 100) String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 255) String fullName,
            @NotBlank @jakarta.validation.constraints.Email @Size(max = 255) String email) {

        /** Không in mật khẩu/email/họ tên khi log. */
        @Override
        public String toString() {
            return "SignupRequest[tenantCode=" + tenantCode + ", username=" + username + "]";
        }
    }

    /** Quên mật khẩu, bước yêu cầu: chỉ tên đăng nhập. */
    public record PasswordResetRequest(@NotBlank @Size(max = 100) String username) {
    }

    /** Quên mật khẩu, bước xác nhận. Không in token/mật khẩu khi log. */
    public record PasswordResetConfirmRequest(@NotBlank @Size(max = 200) String token,
                                              @NotBlank @Size(min = 8, max = 72) String newPassword) {
        @Override
        public String toString() {
            return "PasswordResetConfirmRequest[token=***, newPassword=***]";
        }
    }

    public record SwitchTenantRequest(@NotNull UUID tenantId, @NotBlank String refreshToken) {
    }

    /** tenantId: tenant của phiên (null với phiên nền tảng); availableTenants: các tenant người dùng có thể đổi sang. */
    public record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn,
                                boolean mustChangePassword, UUID tenantId, List<TenantSummary> availableTenants) {

        public TokenResponse {
            availableTenants = availableTenants == null ? List.of() : List.copyOf(availableTenants);
        }

        public static TokenResponse bearer(String accessToken, String refreshToken, long expiresIn) {
            return bearer(accessToken, refreshToken, expiresIn, false);
        }

        public static TokenResponse bearer(String accessToken, String refreshToken, long expiresIn,
                                           boolean mustChangePassword) {
            return bearer(accessToken, refreshToken, expiresIn, mustChangePassword, null, List.of());
        }

        public static TokenResponse bearer(String accessToken, String refreshToken, long expiresIn,
                                           boolean mustChangePassword, UUID tenantId,
                                           List<TenantSummary> availableTenants) {
            return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn, mustChangePassword, tenantId,
                    availableTenants);
        }
    }
}

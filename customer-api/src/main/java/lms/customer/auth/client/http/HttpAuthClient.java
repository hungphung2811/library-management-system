package lms.customer.auth.client.http;

import java.util.Set;
import java.util.UUID;
import lms.customer.auth.client.AuthClient;
import lms.customer.auth.dto.AuthDtos.IdentityTokenView;
import lms.customer.auth.dto.AuthDtos.SignupRequest;
import lms.customer.web.DownstreamErrorCode;
import lms.customer.web.PublicApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Gọi /internal/auth/{login,refresh,logout} của identity (các đường dẫn công khai của identity, không cần token).
 * Đã đối chiếu với AuthController/AuthExceptionHandler/TenantExceptionHandler của identity: 401 UNAUTHORIZED (sai
 * mật khẩu/không có user), 403 NO_TENANT_ACCESS (không thuộc tenant, kể cả mã tenant lạ), 423 ACCOUNT_LOCKED,
 * 403 ACCOUNT_DISABLED. identity kiểm khóa/vô hiệu TRƯỚC mật khẩu nên ở login cả bốn mã đều gộp thành 401 LOGIN_FAILED
 * (ADR-0011 2.13 phương án A), nếu không dò được trạng thái tài khoản bất kỳ mà không cần mật khẩu. Ở refresh thì giữ
 * mã riêng (người gọi đã có refresh token hợp lệ), do PublicExceptionHandler chuyển tiếp.
 */
public class HttpAuthClient implements AuthClient {

    /** Mã lỗi đăng nhập gộp thành LOGIN_FAILED. */
    private static final Set<String> LOGIN_FAILURE_CODES =
            Set.of("UNAUTHORIZED", "NO_TENANT_ACCESS", "ACCOUNT_LOCKED", "ACCOUNT_DISABLED");

    /** Kênh token của khách (identity ghi aud=lms-storefront, giới hạn permissions). */
    static final String STOREFRONT = "STOREFRONT";

    private final RestClient client;
    private final boolean sendTenantId;

    public HttpAuthClient(RestClient client) {
        this(client, true);
    }

    /**
     * @param sendTenantId gửi {@code tenantId} khi làm mới theo cửa hàng (ADR-0013 2.4). Đặt false nếu identity chưa
     *                     nâng cấp mà từ chối trường lạ; khi đó identity KHÔNG kiểm tenant của refresh token.
     */
    public HttpAuthClient(RestClient client, boolean sendTenantId) {
        this.client = client;
        this.sendTenantId = sendTenantId;
    }

    @Override
    public IdentityTokenView login(String tenantCode, String username, String password) {
        try {
            return client.post().uri("/internal/auth/login")
                    .body(new LoginBody(username, password, tenantCode, STOREFRONT))
                    .retrieve().body(IdentityTokenView.class);
        } catch (RestClientResponseException ex) {
            if ((ex.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED) || ex.getStatusCode().isSameCodeAs(HttpStatus.FORBIDDEN)
                    || ex.getStatusCode().isSameCodeAs(HttpStatus.LOCKED))
                    && DownstreamErrorCode.isOneOf(LOGIN_FAILURE_CODES, DownstreamErrorCode.of(ex))) {
                throw new PublicApiException(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED",
                        "Tên đăng nhập hoặc mật khẩu không đúng");
            }
            throw ex;
        }
    }

    @Override
    public void signup(String tenantCode, SignupRequest request) {
        // Body dựng từ record allowlist; tenantCode từ đường dẫn. Không chuyển trường nào khác.
        client.post().uri("/internal/auth/signup")
                .body(new SignupBody(tenantCode, request.username(), request.password(), request.fullName(),
                        request.email()))
                .retrieve().toBodilessEntity();
    }

    @Override
    public void verifyEmail(String username, String email, String otp) {
        client.post().uri("/internal/auth/email-verifications/confirm")
                .body(new VerifyEmailBody(username, email, otp)).retrieve().toBodilessEntity();
    }

    @Override
    public void resendVerification(String username, String email) {
        client.post().uri("/internal/auth/email-verifications/resend")
                .body(new ResendVerificationBody(username, email)).retrieve().toBodilessEntity();
    }

    @Override
    public IdentityTokenView refresh(String refreshToken) {
        return refresh(refreshToken, null);
    }

    @Override
    public IdentityTokenView refresh(String refreshToken, UUID tenantId) {
        return client.post().uri("/internal/auth/refresh")
                .body(new RefreshBody(refreshToken, STOREFRONT, sendTenantId ? tenantId : null)).retrieve()
                .body(IdentityTokenView.class);
    }

    @Override
    public void logout(String refreshToken) {
        client.post().uri("/internal/auth/logout").body(new LogoutBody(refreshToken)).retrieve().toBodilessEntity();
    }

    /** Logout không cần channel/tenant (thu hồi token nào cũng vô hại, idempotent). */
    record LogoutBody(String refreshToken) {
    }

    record LoginBody(String username, String password, String tenantCode, String channel) {
    }

    /** {@code null} bị bỏ khỏi JSON để identity chỉ thấy trường được dùng. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record VerifyEmailBody(String username, String email, String otp) {
        @Override
        public String toString() {
            return "VerifyEmailBody[***]";
        }
    }

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record ResendVerificationBody(String username, String email) {
        @Override
        public String toString() {
            return "ResendVerificationBody[***]";
        }
    }

    record SignupBody(String tenantCode, String username, String password, String fullName, String email) {
        @Override
        public String toString() {
            return "SignupBody[***]";
        }
    }

    /** {@code tenantId} null thì bị bỏ khỏi JSON (identity cũ không nhận trường lạ vẫn chạy được). */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    record RefreshBody(String refreshToken, String channel, UUID tenantId) {
    }
}

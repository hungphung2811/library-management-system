package lms.api.identity.client;

import lms.api.identity.dto.AuthDtos.ChangePasswordRequest;
import lms.api.identity.dto.AuthDtos.CredentialCreated;
import lms.api.identity.dto.AuthDtos.ExternalLoginView;
import lms.api.identity.dto.AuthDtos.ProviderView;
import lms.api.identity.dto.AuthDtos.RegisterCredentialRequest;
import lms.api.identity.dto.AuthDtos.SessionView;
import lms.api.identity.dto.AuthDtos.SwitchTenantRequest;
import lms.api.identity.dto.AuthDtos.TokenView;
import lms.api.identity.dto.UserDtos.RevokedSessionsView;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;

/**
 * Cổng sang API xác thực của identity-service. Các hàm trả {@code ResponseEntity<byte[]>} là phản hồi thô (status,
 * Location, Set-Cookie, body) vì luồng đăng nhập ngoài phụ thuộc vào redirect và cookie, không được tự follow redirect.
 */
public interface AuthClient {

    /** tenantCode có thể null (identity chọn tenant dùng gần nhất). */
    TokenView login(String username, String password, String tenantCode);

    TokenView refresh(String refreshToken);

    void logout(String refreshToken);

    /** Đổi tenant của phiên (cần token); trả cặp token mới của tenant đích. */
    TokenView switchTenant(SwitchTenantRequest request);

    void changePassword(ChangePasswordRequest request);

    /** Quên mật khẩu, yêu cầu (công khai, identity luôn 202). */
    void requestPasswordReset(String username);

    /** Quên mật khẩu, xác nhận (công khai): 400 INVALID_RESET_TOKEN khi token sai/hết hạn/đã dùng. */
    void confirmPasswordReset(String token, String newPassword);

    CredentialCreated register(RegisterCredentialRequest request);

    List<ProviderView> externalProviders();

    /** 302 kèm Location (IdP) và Set-Cookie lms_ext. */
    ResponseEntity<byte[]> externalStart(String provider, String returnUrl);

    /** 302 về FE kèm Set-Cookie xóa cookie, hoặc 400 INVALID_STATE. lmsExtCookie là giá trị cookie lms_ext của trình duyệt. */
    ResponseEntity<byte[]> externalCallback(String provider, String code, String state, String error,
                                            String lmsExtCookie);

    TokenView externalExchange(String code);

    /** JSON {authorizationUrl} kèm Set-Cookie lms_ext. */
    ResponseEntity<byte[]> externalLink(String provider, String returnUrl);

    List<ExternalLoginView> myExternalLogins();

    void unlinkExternal(String provider);

    /** Phiên đăng nhập của chính người gọi (mọi tenant, mọi kênh). */
    List<SessionView> mySessions();

    /** 204; 404 SESSION_NOT_FOUND. */
    void revokeMySession(UUID sessionId);

    /** keepCurrent=true giữ phiên hiện tại; 409 SESSION_NOT_IDENTIFIED khi token không có claim sid. */
    RevokedSessionsView revokeMySessions(boolean keepCurrent);
}

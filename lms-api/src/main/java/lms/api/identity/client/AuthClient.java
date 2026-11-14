package lms.api.identity.client;

import lms.api.identity.dto.AuthDtos.ChangePasswordRequest;
import lms.api.identity.dto.AuthDtos.CredentialCreated;
import lms.api.identity.dto.AuthDtos.ExternalLoginView;
import lms.api.identity.dto.AuthDtos.ProviderView;
import lms.api.identity.dto.AuthDtos.RegisterCredentialRequest;
import lms.api.identity.dto.AuthDtos.TokenView;
import java.util.List;
import org.springframework.http.ResponseEntity;

/**
 * Cổng sang API xác thực của identity-service. Các hàm trả {@code ResponseEntity<byte[]>} là phản hồi thô (status,
 * Location, Set-Cookie, body) vì luồng đăng nhập ngoài phụ thuộc vào redirect và cookie, không được tự follow redirect.
 */
public interface AuthClient {

    TokenView login(String username, String password);

    TokenView refresh(String refreshToken);

    void logout(String refreshToken);

    void changePassword(ChangePasswordRequest request);

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
}

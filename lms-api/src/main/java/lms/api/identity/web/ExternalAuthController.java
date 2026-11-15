package lms.api.identity.web;

import lms.api.identity.client.AuthClient;
import lms.api.identity.dto.AuthDtos.ExchangeRequest;
import lms.api.identity.dto.AuthDtos.ExternalLoginView;
import lms.api.identity.dto.AuthDtos.ProviderView;
import lms.api.identity.dto.AuthDtos.TokenView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Đăng nhập ngoài (OIDC) cho FE. providers/start/callback/exchange là công khai. start, callback và link chuyển nguyên
 * phản hồi của identity-service (status 302, Location, Set-Cookie lms_ext), không tự follow redirect; cookie lms_ext
 * của trình duyệt được gửi sang service ở bước callback. redirect_uri đăng ký ở IdP vẫn là
 * {@code <public-base-url>/external/<tên>/callback} = {@code /api/auth/external/<tên>/callback}.
 */
@RestController
@RequestMapping("/api/auth")
public class ExternalAuthController {

    static final String COOKIE = "lms_ext";

    private final AuthClient auth;

    public ExternalAuthController(AuthClient auth) {
        this.auth = auth;
    }

    @GetMapping("/external/providers")
    public List<ProviderView> providers() {
        return auth.externalProviders();
    }

    @GetMapping("/external/{provider}/start")
    public ResponseEntity<byte[]> start(@PathVariable String provider, @RequestParam String returnUrl) {
        return auth.externalStart(provider, returnUrl);
    }

    @GetMapping("/external/{provider}/callback")
    public ResponseEntity<byte[]> callback(@PathVariable String provider,
                                           @RequestParam(required = false) String code,
                                           @RequestParam(required = false) String state,
                                           @RequestParam(required = false) String error,
                                           @CookieValue(name = COOKIE, required = false) String cookie) {
        return auth.externalCallback(provider, code, state, error, cookie);
    }

    @PostMapping("/external/exchange")
    public ResponseEntity<TokenView> exchange(@Valid @RequestBody ExchangeRequest request) {
        return AuthController.noStore(auth.externalExchange(request.code()));
    }

    /** Trình duyệt không gắn được Authorization khi điều hướng nên trả JSON {authorizationUrl} kèm Set-Cookie. */
    @GetMapping("/me/external/{provider}/link")
    public ResponseEntity<byte[]> link(@PathVariable String provider, @RequestParam String returnUrl) {
        return auth.externalLink(provider, returnUrl);
    }

    @GetMapping("/me/external")
    public List<ExternalLoginView> mine() {
        return auth.myExternalLogins();
    }

    @DeleteMapping("/me/external/{provider}")
    public ResponseEntity<Void> unlink(@PathVariable String provider) {
        auth.unlinkExternal(provider);
        return ResponseEntity.noContent().build();
    }
}

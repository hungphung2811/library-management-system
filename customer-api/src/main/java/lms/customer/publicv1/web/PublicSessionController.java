package lms.customer.publicv1.web;

import jakarta.validation.Valid;
import lms.customer.auth.client.AuthClient;
import lms.customer.auth.dto.AuthDtos.IdentityTokenView;
import lms.customer.auth.dto.AuthDtos.LoginRequest;
import lms.customer.auth.dto.AuthDtos.RefreshRequest;
import lms.customer.auth.dto.AuthDtos.SessionView;
import lms.customer.auth.dto.AuthDtos.SignupRequest;
import lms.customer.publicv1.SiteKeyAccess;
import lms.customer.storefront.Feature;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicApiException;
import lms.customer.web.PublicCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phiên khách của Public API v1 (Public Site Key, P2): ánh xạ {@code /public/t/{code}/auth/*}. Tenant lấy từ site key
 * (không từ đường dẫn), kênh token do BFF đặt là STOREFRONT; lỗi gộp {@code 401 LOGIN_FAILED} như đường cũ (do
 * HttpAuthClient). Phản hồi {@code no-store}, chỉ {accessToken, refreshToken, expiresIn}. Không log thân request.
 */
@RestController
@RequestMapping("/api/public/v1/sessions")
public class PublicSessionController {

    private static final Logger log = LoggerFactory.getLogger(PublicSessionController.class);

    private final AuthClient auth;
    private final SiteKeyAccess siteKeys;

    public PublicSessionController(AuthClient auth, SiteKeyAccess siteKeys) {
        this.auth = auth;
        this.siteKeys = siteKeys;
    }

    /** Đăng nhập. */
    @PostMapping
    public ResponseEntity<SessionView> login(@RequestHeader(value = SiteKeyAccess.HEADER, required = false) String siteKey,
                                             @Valid @RequestBody LoginRequest request) {
        StorefrontAccess shop = siteKeys.resolve(siteKey);
        return PublicCache.noStore(view(auth.login(shop.code(), request.username(), request.password())));
    }

    @PostMapping("/signup")
    public ResponseEntity<Void> signup(@RequestHeader(value = SiteKeyAccess.HEADER, required = false) String siteKey,
                                       @Valid @RequestBody SignupRequest request) {
        StorefrontAccess shop = siteKeys.resolve(siteKey, Feature.SHOP);
        auth.signup(shop.code(), request);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).build();
    }

    /** Identity kiểm refresh token thuộc đúng tenant của site key trước khi xoay vòng; sai tenant thì 401. */
    @PostMapping("/refresh")
    public ResponseEntity<SessionView> refresh(@RequestHeader(value = SiteKeyAccess.HEADER, required = false) String siteKey,
                                               @Valid @RequestBody RefreshRequest request) {
        StorefrontAccess shop = siteKeys.resolve(siteKey);
        return PublicCache.noStore(view(auth.refresh(request.refreshToken(), shop.tenantId())));
    }

    /** Logout không kiểm tenant (thu hồi token nào cũng vô hại), nhưng site key phải khả dụng. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = SiteKeyAccess.HEADER, required = false) String siteKey,
                                       @Valid @RequestBody RefreshRequest request) {
        siteKeys.resolve(siteKey);
        auth.logout(request.refreshToken());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** Phản hồi identity null/thiếu token không bao giờ được trả như thành công: 503 chung. */
    private static SessionView view(IdentityTokenView token) {
        if (token == null || !token.isUsable()) {
            log.warn("identity trả phản hồi đăng nhập thiếu token ({})", token == null ? "null" : "thiếu trường");
            throw PublicApiException.serviceUnavailable();
        }
        return new SessionView(token.accessToken(), token.refreshToken(), token.expiresIn());
    }
}

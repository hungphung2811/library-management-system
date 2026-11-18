package lms.customer.auth.web;

import jakarta.validation.Valid;
import lms.customer.auth.client.AuthClient;
import lms.customer.auth.dto.AuthDtos.IdentityTokenView;
import lms.customer.auth.dto.AuthDtos.LoginRequest;
import lms.customer.auth.dto.AuthDtos.RefreshRequest;
import lms.customer.auth.dto.AuthDtos.SessionView;
import lms.customer.auth.dto.AuthDtos.SignupRequest;
import lms.customer.storefront.Feature;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicApiException;
import lms.customer.web.PublicCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phiên khách đi qua customer-api (ADR-0011 2.2, ADR-0013 2.4): tenant ép theo {code} trong đường dẫn (storefront được
 * phân giải trước, nên tenant không khả dụng không bao giờ chạm tới identity), kênh token do BFF đặt là STOREFRONT, phản
 * hồi chỉ {accessToken, refreshToken, expiresIn}. Không có switch-tenant.
 *
 * <p>Đường dẫn cũ {@code /public/auth/refresh|logout} (không có mã cửa hàng) được giữ để tương thích nhưng không khuyến
 * nghị: refresh không kiểm tenant, CORS chỉ cho domain nền tảng và origin dev.
 */
@RestController
@RequestMapping("/public")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthClient auth;
    private final StorefrontAccessCache storefronts;

    public AuthController(AuthClient auth, StorefrontAccessCache storefronts) {
        this.auth = auth;
        this.storefronts = storefronts;
    }

    @PostMapping("/t/{code}/auth/signup")
    public ResponseEntity<Void> signup(@PathVariable String code, @Valid @RequestBody SignupRequest request) {
        StorefrontAccess shop = storefronts.resolve(code).require(Feature.SHOP);
        auth.signup(shop.code(), request);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/t/{code}/auth/login")
    public ResponseEntity<SessionView> login(@PathVariable String code, @Valid @RequestBody LoginRequest request) {
        StorefrontAccess shop = storefronts.resolve(code);
        return PublicCache.noStore(view(auth.login(shop.code(), request.username(), request.password())));
    }

    /** Identity kiểm refresh token thuộc đúng tenant của {code} trước khi xoay vòng; sai tenant thì 401. */
    @PostMapping("/t/{code}/auth/refresh")
    public ResponseEntity<SessionView> refreshForShop(@PathVariable String code,
                                                      @Valid @RequestBody RefreshRequest request) {
        StorefrontAccess shop = storefronts.resolve(code);
        return PublicCache.noStore(view(auth.refresh(request.refreshToken(), shop.tenantId())));
    }

    /** Logout không kiểm tenant (thu hồi token nào cũng vô hại), nhưng {code} phải khả dụng như mọi /public/t/{code}/**. */
    @PostMapping("/t/{code}/auth/logout")
    public ResponseEntity<Void> logoutForShop(@PathVariable String code, @Valid @RequestBody RefreshRequest request) {
        storefronts.resolve(code);
        auth.logout(request.refreshToken());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/auth/refresh")
    public ResponseEntity<SessionView> refresh(@Valid @RequestBody RefreshRequest request) {
        return PublicCache.noStore(view(auth.refresh(request.refreshToken())));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        auth.logout(request.refreshToken());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** Phản hồi identity null/thiếu token không bao giờ được trả như thành công: 503 chung, log một dòng không kèm dữ liệu. */
    private static SessionView view(IdentityTokenView token) {
        if (token == null || !token.isUsable()) {
            log.warn("identity trả phản hồi đăng nhập thiếu token ({})", token == null ? "null" : "thiếu trường");
            throw PublicApiException.serviceUnavailable();
        }
        return new SessionView(token.accessToken(), token.refreshToken(), token.expiresIn());
    }
}

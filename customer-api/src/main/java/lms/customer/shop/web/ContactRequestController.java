package lms.customer.shop.web;

import jakarta.validation.Valid;
import lms.customer.shop.client.ShopClient;
import lms.customer.shop.dto.ShopDtos.ContactRequestBody;
import lms.customer.storefront.ShopSessionGuard;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicApiException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Khách đã đăng nhập gửi liên hệ tới cửa hàng (ADR-0015). Thứ tự: (1) phân giải {code} (không khả dụng thì 404
 * SHOP_NOT_AVAILABLE), (2) {@link ShopSessionGuard} (token dịch vụ hoặc sai aud thì 401, tenant lệch thì 403
 * SHOP_SESSION_MISMATCH), (3) chuyển tiếp TOKEN KHÁCH tới sales, nơi kiểm khóa ShopContactController/create, module và
 * chống spam. Trả 201 không thân, no-store, kể cả khi sales coi là gửi lại. Không log thân request.
 */
@RestController
@RequestMapping("/public/t/{code}/contact-requests")
public class ContactRequestController {

    private final StorefrontAccessCache storefronts;
    private final ShopSessionGuard guard;
    private final ShopClient shop;

    public ContactRequestController(StorefrontAccessCache storefronts, ShopSessionGuard guard, ShopClient shop) {
        this.storefronts = storefronts;
        this.guard = guard;
        this.shop = shop;
    }

    @PostMapping
    public ResponseEntity<Void> create(@PathVariable String code, @Valid @RequestBody ContactRequestBody request,
                                       Authentication authentication) {
        StorefrontAccess access = storefronts.resolve(code);
        String token = guard.requireSession(access, authentication).getTokenValue();
        if (!Boolean.TRUE.equals(request.consent())) {
            throw new PublicApiException(HttpStatus.BAD_REQUEST, "CONSENT_REQUIRED",
                    "Cần đồng ý xử lý dữ liệu để gửi biểu mẫu");
        }
        shop.submitContactRequest(token, request);
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).build();
    }
}

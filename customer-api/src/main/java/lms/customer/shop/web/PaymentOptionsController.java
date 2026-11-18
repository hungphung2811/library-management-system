package lms.customer.shop.web;

import lms.customer.shop.client.ShopClient;
import lms.customer.shop.dto.ShopDtos.PaymentOptionsView;
import lms.customer.storefront.Feature;
import lms.customer.storefront.ShopSessionGuard;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicCache;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phương thức thanh toán khả dụng của cửa hàng (ADR-0029 3.3). Cần đăng nhập (trang thanh toán): cùng chuỗi kiểm như
 * {@link OrderController} (phân giải {code}, {@link ShopSessionGuard}, chuyển tiếp token khách). Phản hồi {@code no-store}
 * vì SEPAY bật/tắt theo cấu hình.
 */
@RestController
@RequestMapping("/public/t/{code}/payment-options")
public class PaymentOptionsController {

    private final StorefrontAccessCache storefronts;
    private final ShopSessionGuard guard;
    private final ShopClient shop;

    public PaymentOptionsController(StorefrontAccessCache storefronts, ShopSessionGuard guard, ShopClient shop) {
        this.storefronts = storefronts;
        this.guard = guard;
        this.shop = shop;
    }

    @GetMapping
    public ResponseEntity<PaymentOptionsView> options(@PathVariable String code, Authentication authentication) {
        StorefrontAccess access = storefronts.resolve(code).require(Feature.SHOP);
        String token = guard.requireSession(access, authentication).getTokenValue();
        return PublicCache.noStore(shop.paymentOptions(token));
    }
}

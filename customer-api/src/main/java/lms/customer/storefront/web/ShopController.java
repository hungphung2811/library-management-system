package lms.customer.storefront.web;

import java.time.Duration;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.ShopView;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicCache;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Thông tin công khai tối thiểu của storefront, lấy thẳng từ kết quả phân giải (không gọi thêm). */
@RestController
@RequestMapping("/public/t")
public class ShopController {

    static final Duration MAX_AGE = Duration.ofSeconds(30);

    private final StorefrontAccessCache storefronts;

    public ShopController(StorefrontAccessCache storefronts) {
        this.storefronts = storefronts;
    }

    @GetMapping("/{code}")
    public ResponseEntity<ShopView> shop(@PathVariable String code) {
        StorefrontAccess access = storefronts.resolve(code);
        return PublicCache.publicFor(MAX_AGE, new ShopView(access.code(), access.name(),
                access.shopEnabled(), access.libraryEnabled()));
    }
}

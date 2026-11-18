package lms.customer.banner.web;

import java.time.Duration;
import java.util.List;
import lms.customer.banner.BannerCache;
import lms.customer.banner.client.BannerClient;
import lms.customer.banner.dto.BannerDtos.BannerView;
import lms.customer.storefront.Feature;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicCache;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Banner trang chủ công khai (ADR-0019 2.6): {code} không khả dụng thì 404 trước khi gọi sales; đệm 30 giây theo tenant. */
@RestController
@RequestMapping("/public/t/{code}/banners")
public class BannerController {

    static final Duration MAX_AGE = Duration.ofSeconds(60);

    private final StorefrontAccessCache storefronts;
    private final BannerClient banners;
    private final BannerCache cache;

    public BannerController(StorefrontAccessCache storefronts, BannerClient banners, BannerCache cache) {
        this.storefronts = storefronts;
        this.banners = banners;
        this.cache = cache;
    }

    @GetMapping
    public ResponseEntity<List<BannerView>> list(@PathVariable String code) {
        StorefrontAccess shop = storefronts.resolve(code).require(Feature.SHOP);
        return PublicCache.publicFor(MAX_AGE, cache.get(shop.tenantId(), () -> banners.list(shop.accessToken())));
    }
}

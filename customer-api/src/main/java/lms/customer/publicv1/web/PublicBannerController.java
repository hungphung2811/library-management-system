package lms.customer.publicv1.web;

import java.time.Duration;
import java.util.List;
import lms.customer.banner.BannerCache;
import lms.customer.banner.client.BannerClient;
import lms.customer.banner.dto.BannerDtos.BannerView;
import lms.customer.publicv1.SiteKeyAccess;
import lms.customer.storefront.Feature;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicCache;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Banner trang chủ của Public API v1: như {@code /public/t/{code}/banners}, tenant theo {@code X-Site-Key}. */
@RestController
@RequestMapping("/api/public/v1/banners")
public class PublicBannerController {

    static final Duration MAX_AGE = Duration.ofSeconds(60);

    private final SiteKeyAccess siteKeys;
    private final BannerClient banners;
    private final BannerCache cache;

    public PublicBannerController(SiteKeyAccess siteKeys, BannerClient banners, BannerCache cache) {
        this.siteKeys = siteKeys;
        this.banners = banners;
        this.cache = cache;
    }

    @GetMapping
    public ResponseEntity<List<BannerView>> list(
            @RequestHeader(value = SiteKeyAccess.HEADER, required = false) String siteKey) {
        StorefrontAccess shop = siteKeys.resolve(siteKey, Feature.SHOP);
        return PublicCache.publicFor(MAX_AGE, cache.get(shop.tenantId(), () -> banners.list(shop.accessToken())));
    }
}

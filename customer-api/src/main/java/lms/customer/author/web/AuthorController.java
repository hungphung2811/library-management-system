package lms.customer.author.web;

import java.time.Duration;
import java.util.List;
import lms.customer.author.FeaturedAuthors;
import lms.customer.author.dto.AuthorDtos.AuthorView;
import lms.customer.storefront.Feature;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicCache;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tác giả nổi bật công khai: {code} không khả dụng thì 404 SHOP_NOT_AVAILABLE trước khi gọi catalog/sales. */
@RestController
@RequestMapping("/public/t/{code}/authors")
public class AuthorController {

    static final Duration MAX_AGE = Duration.ofSeconds(60);

    private final StorefrontAccessCache storefronts;
    private final FeaturedAuthors featuredAuthors;

    public AuthorController(StorefrontAccessCache storefronts, FeaturedAuthors featuredAuthors) {
        this.storefronts = storefronts;
        this.featuredAuthors = featuredAuthors;
    }

    @GetMapping("/featured")
    public ResponseEntity<List<AuthorView>> featured(@PathVariable String code) {
        StorefrontAccess shop = storefronts.resolve(code).require(Feature.SHOP);
        return PublicCache.publicFor(MAX_AGE, featuredAuthors.featured(shop.accessToken()));
    }
}

package lms.customer.catalog.web;

import java.time.Duration;
import java.util.List;
import lms.customer.catalog.FeaturedBooks;
import lms.customer.catalog.dto.BookDtos.BookSummaryView;
import lms.customer.storefront.Feature;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicCache;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sản phẩm nổi bật công khai (sách bán chạy 3 tháng). Đường dẫn literal {@code /books/featured} được Spring ưu tiên hơn
 * {@code /books/{bookRef}} của BookController. {code} không khả dụng thì 404 SHOP_NOT_AVAILABLE trước khi gọi sales.
 */
@RestController
@RequestMapping("/public/t/{code}/books")
public class FeaturedBookController {

    static final Duration MAX_AGE = Duration.ofSeconds(60);

    private final StorefrontAccessCache storefronts;
    private final FeaturedBooks featuredBooks;

    public FeaturedBookController(StorefrontAccessCache storefronts, FeaturedBooks featuredBooks) {
        this.storefronts = storefronts;
        this.featuredBooks = featuredBooks;
    }

    @GetMapping("/featured")
    public ResponseEntity<List<BookSummaryView>> featured(@PathVariable String code) {
        StorefrontAccess shop = storefronts.resolve(code).require(Feature.SHOP);
        return PublicCache.publicFor(MAX_AGE, featuredBooks.featured(shop.accessToken()));
    }
}

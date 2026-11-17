package lms.customer.catalog.web;

import java.time.Duration;
import java.util.UUID;
import lms.customer.catalog.client.CatalogClient;
import lms.customer.catalog.dto.BookDtos.BookDetailView;
import lms.customer.catalog.dto.BookDtos.BookPageView;
import lms.customer.catalog.dto.BookDtos.BookPreviewView;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicApiException;
import lms.customer.web.PublicCache;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Danh mục sách công khai của cửa hàng (ADR-0011 2.6, 2.8, 2.12). {code} được phân giải trước (không khả dụng thì
 * 404 SHOP_NOT_AVAILABLE, sales không bị gọi); sales được gọi bằng token dịch vụ của tenant, kể cả khi request có
 * header Authorization của khách. Phản hồi chỉ gồm trường allowlist (xem {@code BookDtos}).
 */
@RestController
@RequestMapping("/public/t/{code}/books")
public class BookController {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;
    static final int MAX_KEYWORD_LENGTH = 100;
    static final Duration LIST_MAX_AGE = Duration.ofSeconds(30);
    static final Duration PREVIEW_MAX_AGE = Duration.ofSeconds(60);

    /** Query gom thành một record (CLAUDE.md): Spring tự bind. */
    public record BookQuery(String keyword, Integer page, Integer size) {
    }

    private final StorefrontAccessCache storefronts;
    private final CatalogClient catalog;

    public BookController(StorefrontAccessCache storefronts, CatalogClient catalog) {
        this.storefronts = storefronts;
        this.catalog = catalog;
    }

    @GetMapping
    public ResponseEntity<BookPageView> list(@PathVariable String code, BookQuery query) {
        StorefrontAccess shop = storefronts.resolve(code);
        int page = query.page() == null || query.page() < 0 ? 0 : query.page();
        int size = query.size() == null || query.size() < 1 ? DEFAULT_SIZE : Math.min(query.size(), MAX_SIZE);
        return PublicCache.publicFor(LIST_MAX_AGE, catalog.list(shop.accessToken(), keyword(query.keyword()), page, size));
    }

    @GetMapping("/{bookRef}")
    public ResponseEntity<BookDetailView> get(@PathVariable String code, @PathVariable String bookRef) {
        StorefrontAccess shop = storefronts.resolve(code);
        return PublicCache.publicFor(LIST_MAX_AGE, catalog.get(shop.accessToken(), bookRef(bookRef)));
    }

    @GetMapping("/{bookRef}/preview")
    public ResponseEntity<BookPreviewView> preview(@PathVariable String code, @PathVariable String bookRef) {
        StorefrontAccess shop = storefronts.resolve(code);
        return PublicCache.publicFor(PREVIEW_MAX_AGE, catalog.preview(shop.accessToken(), bookRef(bookRef)));
    }

    /** Từ khóa: cắt khoảng trắng, rỗng thì bỏ; quá dài hoặc có ký tự điều khiển thì 400. */
    private static String keyword(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_KEYWORD_LENGTH || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new PublicApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Dữ liệu không hợp lệ");
        }
        return trimmed;
    }

    /** bookRef không phải UUID thì không thể tồn tại: 404 BOOK_NOT_FOUND (cùng thân với sách không có), không gọi sales. */
    private static UUID bookRef(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new PublicApiException(HttpStatus.NOT_FOUND, "BOOK_NOT_FOUND", "Không tìm thấy sách");
        }
    }
}

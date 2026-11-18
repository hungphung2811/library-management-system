package lms.customer.publicv1.web;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lms.customer.catalog.client.CatalogClient;
import lms.customer.catalog.dto.BookDtos.BookDetailView;
import lms.customer.catalog.dto.BookDtos.BookPageView;
import lms.customer.catalog.dto.BookDtos.BookPreviewView;
import lms.customer.catalog.dto.BookDtos.SuggestionView;
import lms.customer.publicv1.SiteKeyAccess;
import lms.customer.storefront.Feature;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.PublicApiException;
import lms.customer.web.PublicCache;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Danh mục sách của Public API v1 (Public Site Key, P2): cùng hợp đồng và cùng client/DTO với
 * {@code /public/t/{code}/books}, nhưng tenant xác định bằng header {@code X-Site-Key}. Kiểm tham số chặt TRƯỚC khi
 * phân giải site key; site key không hợp lệ thì 404 SHOP_NOT_AVAILABLE (sales không bị gọi). Chỉ đọc bằng token dịch vụ
 * của tenant; phản hồi chỉ gồm trường allowlist của {@code BookDtos}.
 */
@RestController
@RequestMapping("/api/public/v1/books")
public class PublicBookController {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;
    static final int MAX_KEYWORD_LENGTH = 100;
    static final Duration LIST_MAX_AGE = Duration.ofSeconds(30);
    static final Duration SUGGEST_MAX_AGE = Duration.ofSeconds(15);
    static final Duration PREVIEW_MAX_AGE = Duration.ofSeconds(60);
    static final int MIN_SUGGEST_LENGTH = 2;
    static final int MAX_SUGGEST_LENGTH = 50;
    static final int DEFAULT_SUGGEST_LIMIT = 5;
    static final int MAX_SUGGEST_LIMIT = 8;
    static final Set<String> SORTS = Set.of("newest", "price_asc", "price_desc");
    static final Set<String> GROUPS = Set.of("EDITION", "WORK");

    /** Query gom thành một record (CLAUDE.md): keyword, page, size (<= 50), sort. */
    public record BookQuery(String keyword, String sort, String inStock, String group, Integer page, Integer size) {
    }

    public record SuggestionQuery(String q, Integer limit) {
    }

    private final SiteKeyAccess siteKeys;
    private final CatalogClient catalog;

    public PublicBookController(SiteKeyAccess siteKeys, CatalogClient catalog) {
        this.siteKeys = siteKeys;
        this.catalog = catalog;
    }

    @GetMapping
    public ResponseEntity<BookPageView> list(@RequestHeader(value = SiteKeyAccess.HEADER, required = false) String siteKey,
                                             BookQuery query) {
        String keyword = keyword(query.keyword());
        String sort = oneOf(query.sort(), SORTS);
        boolean inStock = inStock(query.inStock());
        String group = oneOf(query.group(), GROUPS);
        StorefrontAccess shop = siteKeys.resolve(siteKey, Feature.SHOP);
        int page = query.page() == null || query.page() < 0 ? 0 : query.page();
        int size = query.size() == null || query.size() < 1 ? DEFAULT_SIZE : Math.min(query.size(), MAX_SIZE);
        return PublicCache.publicFor(LIST_MAX_AGE,
                catalog.list(shop.accessToken(), keyword, sort, inStock, group, page, size));
    }

    @GetMapping("/suggestions")
    public ResponseEntity<List<SuggestionView>> suggestions(
            @RequestHeader(value = SiteKeyAccess.HEADER, required = false) String siteKey, SuggestionQuery query) {
        String q = suggestionText(query.q());
        int limit = query.limit() == null || query.limit() < 1 ? DEFAULT_SUGGEST_LIMIT
                : Math.min(query.limit(), MAX_SUGGEST_LIMIT);
        StorefrontAccess shop = siteKeys.resolve(siteKey, Feature.SHOP);
        return PublicCache.publicFor(SUGGEST_MAX_AGE, catalog.suggestions(shop.accessToken(), q, limit));
    }

    @GetMapping("/{bookRef}")
    public ResponseEntity<BookDetailView> get(@RequestHeader(value = SiteKeyAccess.HEADER, required = false) String siteKey,
                                              @PathVariable String bookRef) {
        StorefrontAccess shop = siteKeys.resolve(siteKey, Feature.SHOP);
        return PublicCache.publicFor(LIST_MAX_AGE, catalog.get(shop.accessToken(), bookRef(bookRef)));
    }

    @GetMapping("/{bookRef}/preview")
    public ResponseEntity<BookPreviewView> preview(
            @RequestHeader(value = SiteKeyAccess.HEADER, required = false) String siteKey, @PathVariable String bookRef) {
        StorefrontAccess shop = siteKeys.resolve(siteKey, Feature.SHOP);
        return PublicCache.publicFor(PREVIEW_MAX_AGE, catalog.preview(shop.accessToken(), bookRef(bookRef)));
    }

    private static PublicApiException invalid() {
        return new PublicApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Dữ liệu không hợp lệ");
    }

    /** Vắng = null; chỉ nhận đúng giá trị trong tập (phân biệt hoa thường), còn lại 400. */
    private static String oneOf(String raw, Set<String> allowed) {
        if (raw == null) {
            return null;
        }
        if (!allowed.contains(raw)) {
            throw invalid();
        }
        return raw;
    }

    /** Vắng = false; chỉ nhận đúng "true", còn lại 400. */
    private static boolean inStock(String raw) {
        if (raw == null) {
            return false;
        }
        if (!"true".equals(raw)) {
            throw invalid();
        }
        return true;
    }

    /** q bắt buộc, cắt khoảng trắng, 2..50 code point, không ký tự điều khiển. */
    private static String suggestionText(String raw) {
        if (raw == null) {
            throw invalid();
        }
        String trimmed = raw.trim();
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < MIN_SUGGEST_LENGTH || length > MAX_SUGGEST_LENGTH
                || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        return trimmed;
    }

    /** Từ khóa: cắt khoảng trắng, rỗng thì bỏ; quá dài hoặc có ký tự điều khiển thì 400. */
    private static String keyword(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_KEYWORD_LENGTH || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        return trimmed;
    }

    /** bookRef không phải UUID thì không thể tồn tại: 404 BOOK_NOT_FOUND (cùng thân), không gọi sales. */
    private static UUID bookRef(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new PublicApiException(HttpStatus.NOT_FOUND, "BOOK_NOT_FOUND", "Không tìm thấy sách");
        }
    }
}

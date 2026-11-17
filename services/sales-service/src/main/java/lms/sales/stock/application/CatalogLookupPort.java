package lms.sales.stock.application;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tra nhiều đầu sách của catalog một lần. Id không thấy thì không có trong map. catalog lỗi: adapter ném
 * SalesException(CATALOG_UNAVAILABLE). Gọi TRƯỚC khi mở transaction.
 */
public interface CatalogLookupPort {

    Map<UUID, CatalogTitle> lookup(Collection<UUID> titleIds);

    /** Tra theo từ khóa (tên/tác giả), tối đa 200 đầu sách (catalog giới hạn). Mặc định rỗng cho bản giả test. */
    default List<CatalogTitle> lookupByKeyword(String keyword) {
        return List.of();
    }

    /** Mô tả và đoạn đọc thử của một đầu sách; không có thì rỗng. */
    default Optional<CatalogPreview> preview(UUID titleId) {
        return Optional.empty();
    }

    record CatalogTitle(UUID id, String name, String author, String isbn, BigDecimal coverPrice, String description,
                        boolean hasPreview) {
        public CatalogTitle(UUID id, String name, String author, String isbn, BigDecimal coverPrice) {
            this(id, name, author, isbn, coverPrice, null, false);
        }
    }

    record CatalogPreview(UUID id, String name, String previewText) {
    }
}

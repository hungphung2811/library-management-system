package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.Title;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Đầu sách trả cho client. {@code author} là chuỗi tự do cũ (giữ nguyên); publisherId/categoryId/authors là phân loại
 * chuẩn tùy chọn (null / rỗng nếu chưa gán).
 */
public record TitleResponse(UUID id, String name, String author, String isbn, BigDecimal coverPrice,
                            long availableCopies, UUID tenantId, UUID publisherId, String publisherName,
                            UUID categoryId, String categoryName, List<AuthorRef> authors) {

    /** Dạng không kèm phân loại chuẩn (tương thích cũ). */
    public TitleResponse(UUID id, String name, String author, String isbn, BigDecimal coverPrice,
                         long availableCopies, UUID tenantId) {
        this(id, name, author, isbn, coverPrice, availableCopies, tenantId, null, null, null, null, List.of());
    }

    public static TitleResponse from(Title title, long availableCopies) {
        return new TitleResponse(title.id(), title.name(), title.author(), title.isbn(), title.coverPrice(),
                availableCopies, title.tenantId(), title.publisherId(), null, title.categoryId(), null, List.of());
    }

    public static TitleResponse from(Title title, long availableCopies, TitleClassificationService.View view) {
        TitleClassificationService.View v = view == null ? TitleClassificationService.View.EMPTY : view;
        return new TitleResponse(title.id(), title.name(), title.author(), title.isbn(), title.coverPrice(),
                availableCopies, title.tenantId(), title.publisherId(), v.publisherName(), title.categoryId(),
                v.categoryName(), v.authors());
    }
}

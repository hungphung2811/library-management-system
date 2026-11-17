package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.Title;
import java.math.BigDecimal;
import java.util.UUID;

/** Không có previewText (tránh kéo 100 x 2000 ký tự mỗi lần); chỉ {@code hasPreview}.
 * Thông tin tối thiểu của đầu sách cho service khác (sales): không số bản sao, không tenantId. */
public record TitleLookupView(UUID id, String name, String author, String isbn, BigDecimal coverPrice,
                              String description, boolean hasPreview) {

    public static TitleLookupView from(Title title) {
        return new TitleLookupView(title.id(), title.name(), title.author(), title.isbn(), title.coverPrice(),
                title.description(), title.hasPreview());
    }
}

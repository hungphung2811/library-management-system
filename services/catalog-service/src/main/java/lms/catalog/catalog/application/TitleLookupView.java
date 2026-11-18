package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleExtras;
import lms.catalog.catalog.domain.TitleFormat;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Không có previewText (tránh kéo 100 x 2000 ký tự mỗi lần); chỉ {@code hasPreview}.
 * Thông tin tối thiểu của đầu sách cho service khác (sales): không số bản sao, không tenantId. */
public record TitleLookupView(UUID id, String name, String author, String isbn, BigDecimal coverPrice,
                              String description, boolean hasPreview,
                              String subtitle, Integer publicationYear, String edition, String language,
                              Integer pageCount, TitleFormat format, String translator, String series,
                              Integer seriesNumber, String coverImageUrl, List<String> tags) {

    /** Dạng 7 trường (chưa có thông tin bổ sung). */
    public TitleLookupView(UUID id, String name, String author, String isbn, BigDecimal coverPrice,
                           String description, boolean hasPreview) {
        this(id, name, author, isbn, coverPrice, description, hasPreview, null, null, null, null, null, null, null,
                null, null, null, List.of());
    }

    public static TitleLookupView from(Title title) {
        TitleExtras x = title.extras();
        return new TitleLookupView(title.id(), title.name(), title.author(), title.isbn(), title.coverPrice(),
                title.description(), title.hasPreview(), x.subtitle(), x.publicationYear(), x.edition(), x.language(),
                x.pageCount(), x.format(), x.translator(), x.series(), x.seriesNumber(), x.coverImageUrl(), x.tags());
    }
}

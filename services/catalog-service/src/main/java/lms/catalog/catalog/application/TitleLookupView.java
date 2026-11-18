package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleExtras;
import lms.catalog.catalog.domain.TitleFormat;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Không có previewText (tránh kéo 100 x 2000 ký tự mỗi lần); chỉ {@code hasPreview}.
 * Thông tin tối thiểu của ấn bản (đầu sách) cho service khác (sales): không số bản sao, không tenantId.
 * Từ ADR-0026 thêm workId, workTitle, seriesId, seriesName, partNumber, partLabel (null khi ấn bản chưa gắn tác phẩm). */
public record TitleLookupView(UUID id, String name, String author, String isbn, BigDecimal coverPrice,
                              String description, boolean hasPreview,
                              String subtitle, Integer publicationYear, String edition, String language,
                              Integer pageCount, TitleFormat format, String translator, String series,
                              Integer seriesNumber, String coverImageUrl, List<String> tags,
                              UUID workId, String workTitle, UUID seriesId, String seriesName, Integer partNumber,
                              String partLabel) {

    /** Dạng 18 trường (chưa có trường tác phẩm). */
    public TitleLookupView(UUID id, String name, String author, String isbn, BigDecimal coverPrice,
                           String description, boolean hasPreview,
                           String subtitle, Integer publicationYear, String edition, String language,
                           Integer pageCount, TitleFormat format, String translator, String series,
                           Integer seriesNumber, String coverImageUrl, List<String> tags) {
        this(id, name, author, isbn, coverPrice, description, hasPreview, subtitle, publicationYear, edition, language,
                pageCount, format, translator, series, seriesNumber, coverImageUrl, tags, null, null, null, null,
                null, null);
    }

    /** Dạng 7 trường (chưa có thông tin bổ sung). */
    public TitleLookupView(UUID id, String name, String author, String isbn, BigDecimal coverPrice,
                           String description, boolean hasPreview) {
        this(id, name, author, isbn, coverPrice, description, hasPreview, null, null, null, null, null, null, null,
                null, null, null, List.of());
    }

    public static TitleLookupView from(Title title) {
        return from(title, null);
    }

    /** Kèm tác phẩm đã tra (null thì các trường tác phẩm chỉ có workId/partNumber/partLabel của chính ấn bản). */
    public static TitleLookupView from(Title title, WorkService.WorkView work) {
        TitleExtras x = title.extras();
        String series = x.series();
        Integer seriesNumber = x.seriesNumber();
        UUID seriesId = null;
        String seriesName = null;
        String workTitle = null;
        if (work != null) {
            seriesId = work.work().seriesId();
            seriesName = work.seriesName();
            workTitle = work.work().title();
            if (seriesName != null) {
                series = seriesName;
                seriesNumber = work.work().seriesNumber();
            }
        }
        return new TitleLookupView(title.id(), title.name(), title.author(), title.isbn(), title.coverPrice(),
                title.description(), title.hasPreview(), x.subtitle(), x.publicationYear(), x.edition(), x.language(),
                x.pageCount(), x.format(), x.translator(), series, seriesNumber, x.coverImageUrl(), x.tags(),
                title.workId(), workTitle, seriesId, seriesName, title.partNumber(), title.partLabel());
    }
}

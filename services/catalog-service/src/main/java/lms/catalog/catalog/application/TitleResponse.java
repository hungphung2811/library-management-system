package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleExtras;
import lms.catalog.catalog.domain.TitleFormat;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Ấn bản (đầu sách) trả cho client. {@code author} là chuỗi tự do cũ (giữ nguyên); publisherId/categoryId/authors là phân loại
 * chuẩn tùy chọn (null / rỗng nếu chưa gán). Từ ADR-0026 thêm các trường tác phẩm: workId, workTitle, originalTitle,
 * originalLanguage, seriesId, seriesName, partNumber, partLabel và workAvailableCopies (số bản sao sẵn có của mọi ấn bản
 * cùng tác phẩm CÙNG NGÔN NGỮ với ấn bản này; null khi ấn bản chưa gắn tác phẩm). {@code series}, {@code seriesNumber},
 * {@code categoryId}, {@code authors} lấy từ tác phẩm khi ấn bản đã gắn tác phẩm.
 */
public record TitleResponse(UUID id, String name, String author, String isbn, BigDecimal coverPrice,
                            long availableCopies, UUID tenantId, UUID publisherId, String publisherName,
                            UUID categoryId, String categoryName, List<AuthorRef> authors,
                            String subtitle, Integer publicationYear, String edition, String language,
                            Integer pageCount, TitleFormat format, String translator, String series,
                            Integer seriesNumber, String coverImageUrl, List<String> tags,
                            UUID workId, String workTitle, String originalTitle, String originalLanguage,
                            UUID seriesId, String seriesName, Integer partNumber, String partLabel,
                            Long workAvailableCopies) {

    /** Dạng 23 trường (chưa có trường tác phẩm). */
    public TitleResponse(UUID id, String name, String author, String isbn, BigDecimal coverPrice,
                         long availableCopies, UUID tenantId, UUID publisherId, String publisherName,
                         UUID categoryId, String categoryName, List<AuthorRef> authors,
                         String subtitle, Integer publicationYear, String edition, String language,
                         Integer pageCount, TitleFormat format, String translator, String series,
                         Integer seriesNumber, String coverImageUrl, List<String> tags) {
        this(id, name, author, isbn, coverPrice, availableCopies, tenantId, publisherId, publisherName, categoryId,
                categoryName, authors, subtitle, publicationYear, edition, language, pageCount, format, translator,
                series, seriesNumber, coverImageUrl, tags, null, null, null, null, null, null, null, null, null);
    }

    /** Dạng 12 trường (chưa có thông tin bổ sung): các trường bổ sung null, tags rỗng. */
    public TitleResponse(UUID id, String name, String author, String isbn, BigDecimal coverPrice,
                         long availableCopies, UUID tenantId, UUID publisherId, String publisherName,
                         UUID categoryId, String categoryName, List<AuthorRef> authors) {
        this(id, name, author, isbn, coverPrice, availableCopies, tenantId, publisherId, publisherName, categoryId,
                categoryName, authors, null, null, null, null, null, null, null, null, null, null, List.of());
    }

    /** Dạng không kèm phân loại chuẩn (tương thích cũ). */
    public TitleResponse(UUID id, String name, String author, String isbn, BigDecimal coverPrice,
                         long availableCopies, UUID tenantId) {
        this(id, name, author, isbn, coverPrice, availableCopies, tenantId, null, null, null, null, List.of());
    }

    public static TitleResponse from(Title title, long availableCopies) {
        return withExtras(new TitleResponse(title.id(), title.name(), title.author(), title.isbn(), title.coverPrice(),
                availableCopies, title.tenantId(), title.publisherId(), null, title.categoryId(), null, List.of()), title);
    }

    public static TitleResponse from(Title title, long availableCopies, TitleClassificationService.View view) {
        TitleClassificationService.View v = view == null ? TitleClassificationService.View.EMPTY : view;
        return withExtras(new TitleResponse(title.id(), title.name(), title.author(), title.isbn(), title.coverPrice(),
                availableCopies, title.tenantId(), title.publisherId(), v.publisherName(), title.categoryId(),
                v.categoryName(), v.authors()), title);
    }

    /** Bản kèm thông tin tác phẩm (info null thì giữ nguyên: ấn bản chưa gắn tác phẩm). */
    public TitleResponse withWork(WorkService.WorkInfo info) {
        if (info == null) return this;
        return new TitleResponse(id, name, author, isbn, coverPrice, availableCopies, tenantId, publisherId,
                publisherName, categoryId, categoryName, authors, subtitle, publicationYear, edition, language,
                pageCount, format, translator, series, seriesNumber, coverImageUrl, tags, info.workId(),
                info.workTitle(), info.originalTitle(), info.originalLanguage(), info.seriesId(), info.seriesName(),
                partNumber, partLabel, info.workAvailableCopies());
    }

    private static TitleResponse withExtras(TitleResponse base, Title title) {
        TitleExtras x = title.extras();
        return new TitleResponse(base.id, base.name, base.author, base.isbn, base.coverPrice, base.availableCopies,
                base.tenantId, base.publisherId, base.publisherName, base.categoryId, base.categoryName, base.authors,
                x.subtitle(), x.publicationYear(), x.edition(), x.language(), x.pageCount(), x.format(),
                x.translator(), x.series(), x.seriesNumber(), x.coverImageUrl(), x.tags(), title.workId(), null, null,
                null, null, null, title.partNumber(), title.partLabel(), null);
    }
}

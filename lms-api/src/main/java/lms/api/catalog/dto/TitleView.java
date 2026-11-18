package lms.api.catalog.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Đầu sách trả cho FE; availableCopies là số bản sao sẵn có; tenantId là tenant của dòng (ADR-0008).
 * Thông tin bổ sung tùy chọn: subtitle, publicationYear, edition, language, pageCount, format (PAPERBACK|HARDCOVER|EBOOK),
 * translator, series, seriesNumber, coverImageUrl, tags (null / rỗng nếu chưa nhập).
 * {@code author} là chuỗi tự do cũ; publisher*, category*, authors là phân loại chuẩn tùy chọn (null / rỗng nếu chưa gán).
 */
public record TitleView(UUID id, String name, String author, String isbn, BigDecimal coverPrice, long availableCopies,
                        UUID tenantId, UUID publisherId, String publisherName, UUID categoryId, String categoryName,
                        List<AuthorRefView> authors, String subtitle, Integer publicationYear, String edition,
                        String language, Integer pageCount, String format, String translator, String series,
                        Integer seriesNumber, String coverImageUrl, List<String> tags) {

    /** Dạng 12 trường (chưa có thông tin bổ sung): các trường bổ sung null, tags rỗng. */
    public TitleView(UUID id, String name, String author, String isbn, BigDecimal coverPrice, long availableCopies,
                     UUID tenantId, UUID publisherId, String publisherName, UUID categoryId, String categoryName,
                     List<AuthorRefView> authors) {
        this(id, name, author, isbn, coverPrice, availableCopies, tenantId, publisherId, publisherName, categoryId,
                categoryName, authors, null, null, null, null, null, null, null, null, null, null, List.of());
    }

    /** Dạng không kèm phân loại chuẩn (tương thích cũ). */
    public TitleView(UUID id, String name, String author, String isbn, BigDecimal coverPrice, long availableCopies,
                     UUID tenantId) {
        this(id, name, author, isbn, coverPrice, availableCopies, tenantId, null, null, null, null, List.of());
    }
}

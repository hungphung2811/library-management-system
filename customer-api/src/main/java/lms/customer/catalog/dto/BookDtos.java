package lms.customer.catalog.dto;

import java.util.List;
import java.util.UUID;

/**
 * DTO công khai của danh mục sách (ADR-0011 2.6, 2.8). ĐÚNG các trường dưới đây, test so tập tên trường. KHÔNG bao giờ
 * có: số tồn (onHand, reserved, available), giá nhập, ngưỡng tồn, tenantId, ISBN. Thông tin bổ sung công khai (tùy chọn, null/rỗng nếu chưa nhập): subtitle,
 * publicationYear, edition, language, pageCount, format (PAPERBACK|HARDCOVER|EBOOK), translator, series, seriesNumber,
 * coverImageUrl (http/https), tags. {@code bookRef} chính là
 * {@code titleId} (UUID) mà khách gửi khi đặt hàng.
 */
public final class BookDtos {

    private BookDtos() {
    }

    /** Một mục của danh sách. {@code price} là số nguyên VND đã tính ở sales; {@code inStock} = còn hàng. */
    public record BookSummaryView(UUID bookRef, String title, String author, long price, boolean inStock,
                                  boolean hasPreview, String subtitle, Integer publicationYear, String edition,
                                  String language, Integer pageCount, String format, String translator,
                                  String series, Integer seriesNumber, String coverImageUrl, List<String> tags) {
        public BookSummaryView(UUID bookRef, String title, String author, long price, boolean inStock,
                               boolean hasPreview) {
            this(bookRef, title, author, price, inStock, hasPreview, null, null, null, null, null, null, null, null,
                    null, null, List.of());
        }
    }

    /** Chi tiết: như danh sách + {@code description} (văn bản thuần, luôn có khóa, null nếu chưa nhập). */
    public record BookDetailView(UUID bookRef, String title, String author, long price, boolean inStock,
                                 boolean hasPreview, String description, String subtitle, Integer publicationYear,
                                 String edition, String language, Integer pageCount, String format,
                                 String translator, String series, Integer seriesNumber, String coverImageUrl,
                                 List<String> tags) {
        public BookDetailView(UUID bookRef, String title, String author, long price, boolean inStock,
                              boolean hasPreview, String description) {
            this(bookRef, title, author, price, inStock, hasPreview, description, null, null, null, null, null, null,
                    null, null, null, null, List.of());
        }
    }

    /** Đoạn đọc thử (văn bản thuần). Chỉ có ở endpoint preview, không bao giờ trong danh sách hay chi tiết. */
    public record BookPreviewView(UUID bookRef, String title, String previewText) {
    }

    public record BookPageView(List<BookSummaryView> items, int page, int size, long total) {
    }

    /** Gợi ý tìm kiếm (ADR-0019 2.4): ĐÚNG 3 trường, không giá, không tồn, không mô tả. */
    public record SuggestionView(UUID bookRef, String title, String author) {
    }
}

package lms.customer.catalog.dto;

import java.util.List;
import java.util.UUID;

/**
 * DTO công khai của danh mục sách (ADR-0011 2.6, 2.8). ĐÚNG các trường dưới đây, test so tập tên trường. KHÔNG bao giờ
 * có: số tồn (onHand, reserved, available), giá nhập, ngưỡng tồn, tenantId, ISBN, ảnh. {@code bookRef} chính là
 * {@code titleId} (UUID) mà khách gửi khi đặt hàng.
 */
public final class BookDtos {

    private BookDtos() {
    }

    /** Một mục của danh sách. {@code price} là số nguyên VND đã tính ở sales; {@code inStock} = còn hàng. */
    public record BookSummaryView(UUID bookRef, String title, String author, long price, boolean inStock,
                                  boolean hasPreview) {
    }

    /** Chi tiết: như danh sách + {@code description} (văn bản thuần, luôn có khóa, null nếu chưa nhập). */
    public record BookDetailView(UUID bookRef, String title, String author, long price, boolean inStock,
                                 boolean hasPreview, String description) {
    }

    /** Đoạn đọc thử (văn bản thuần). Chỉ có ở endpoint preview, không bao giờ trong danh sách hay chi tiết. */
    public record BookPreviewView(UUID bookRef, String title, String previewText) {
    }

    public record BookPageView(List<BookSummaryView> items, int page, int size, long total) {
    }
}

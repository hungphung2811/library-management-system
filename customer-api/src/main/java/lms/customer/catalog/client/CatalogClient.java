package lms.customer.catalog.client;

import java.util.List;
import java.util.UUID;
import lms.customer.catalog.dto.BookDtos.SuggestionView;
import lms.customer.catalog.dto.BookDtos.BookDetailView;
import lms.customer.catalog.dto.BookDtos.BookPageView;
import lms.customer.catalog.dto.BookDtos.BookPreviewView;
import lms.customer.catalog.dto.BookDtos.BookSummaryView;

/**
 * Cổng sang sales {@code /internal/sales/storefront/**} (StorefrontController). Luôn gọi bằng TOKEN DỊCH VỤ storefront
 * của tenant (đọc công khai không phụ thuộc người xem, ADR-0011 2.4), kể cả khi request của khách có Authorization.
 * Kết quả chỉ chứa trường allowlist của {@code BookDtos}.
 */
public interface CatalogClient {

    /**
     * @param sort null hoặc đúng một trong newest|price_asc|price_desc (đã kiểm bởi người gọi, gửi nguyên sang sales)
     * @param inStock true = chỉ sách còn hàng (false = không lọc, không gửi tham số)
     * @param group null (= EDITION, không gửi tham số) hoặc đúng EDITION|WORK (đã kiểm bởi người gọi, gửi nguyên sang sales)
     * @param page tính từ 0; @param size đã chặn trong 1..50 bởi người gọi.
     */
    BookPageView list(String serviceToken, String keyword, String sort, boolean inStock, String group, int page,
                      int size);

    /**
     * Thẻ sách theo danh sách id (1..20, không trùng) bằng MỘT lời gọi sales {@code items/by-ids}: giữ thứ tự {@code ids},
     * id không còn bán bị bỏ qua.
     */
    List<BookSummaryView> byIds(String serviceToken, List<UUID> ids);

    /** Gợi ý tìm kiếm (ADR-0019): {@code q} đã trim và kiểm 2..50 ký tự, {@code limit} đã chặn 1..8 bởi người gọi. */
    List<SuggestionView> suggestions(String serviceToken, String q, int limit);

    /** @throws lms.customer.web.PublicApiException hoặc lỗi downstream 404 BOOK_NOT_FOUND khi không có/không đang bán. */
    BookDetailView get(String serviceToken, UUID bookRef);

    BookPreviewView preview(String serviceToken, UUID bookRef);
}

package lms.customer.catalog.client;

import java.util.UUID;
import lms.customer.catalog.dto.BookDtos.BookDetailView;
import lms.customer.catalog.dto.BookDtos.BookPageView;
import lms.customer.catalog.dto.BookDtos.BookPreviewView;

/**
 * Cổng sang sales {@code /internal/sales/storefront/**} (StorefrontController). Luôn gọi bằng TOKEN DỊCH VỤ storefront
 * của tenant (đọc công khai không phụ thuộc người xem, ADR-0011 2.4), kể cả khi request của khách có Authorization.
 * Kết quả chỉ chứa trường allowlist của {@code BookDtos}.
 */
public interface CatalogClient {

    /** @param page tính từ 0; @param size đã chặn trong 1..50 bởi người gọi. */
    BookPageView list(String serviceToken, String keyword, int page, int size);

    /** @throws lms.customer.web.PublicApiException hoặc lỗi downstream 404 BOOK_NOT_FOUND khi không có/không đang bán. */
    BookDetailView get(String serviceToken, UUID bookRef);

    BookPreviewView preview(String serviceToken, UUID bookRef);
}

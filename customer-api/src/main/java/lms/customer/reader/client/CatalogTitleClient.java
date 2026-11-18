package lms.customer.reader.client;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Tra tên đầu sách theo lô ({@code POST /internal/books/lookup} của catalog) bằng TOKEN DỊCH VỤ của tenant. */
public interface CatalogTitleClient {

    /**
     * Tên sách theo titleId. Không bao giờ ném vì lỗi phía catalog (catalog chết, 401/403, 5xx, bulkhead đầy): trả map
     * thiếu mục để tên sách là null mà vẫn trả được danh sách đặt trước. Không gọi catalog khi {@code titleIds} rỗng.
     */
    Map<UUID, String> titleNames(String serviceToken, Collection<UUID> titleIds);
}

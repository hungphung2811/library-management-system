package lms.customer.author.client;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Cổng sang catalog bằng TOKEN DỊCH VỤ của tenant: tác giả có ấn bản mới và tra tác giả theo lô. */
public interface AuthorCatalogClient {

    /** {@code GET /internal/books/new-authors?days&limit}: tên tác giả (distinct), ấn bản mới nhất trước. */
    List<String> newAuthors(String serviceToken, int days, int limit);

    /**
     * {@code POST /internal/books/lookup} theo lô (tối đa 100 id mỗi lời gọi, tự chia lô nếu nhiều hơn): titleId thành
     * tên tác giả (bỏ id không có hoặc tác giả trống). Không gọi catalog khi {@code titleIds} rỗng. Lỗi catalog được ném.
     */
    Map<UUID, String> authorsByTitle(String serviceToken, Collection<UUID> titleIds);
}

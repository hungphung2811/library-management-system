package lms.integration.catalog.client;

import java.util.List;
import java.util.UUID;
import lms.integration.catalog.dto.BookDtos.BookView;

/** Cổng tới catalog-service (đọc đầu sách). Mọi lời gọi chuyển token integration của người gọi. */
public interface CatalogClient {

    /** {@code 404 NOT_FOUND} khi không có (hoặc thuộc tenant khác: RLS theo token). */
    BookView get(UUID id);

    /** Chỉ trả các đầu sách tìm thấy (id lạ bị bỏ qua), tối đa 100 id. */
    List<BookView> lookup(List<UUID> ids);

    /** Chỉ trả các ISBN tìm thấy, tối đa 100 ISBN. */
    List<BookView> lookupByIsbn(List<String> isbns);
}

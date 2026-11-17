package lms.api.dashboard.client;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Tra tên đầu sách theo lô ({@code POST /internal/books/lookup}); id không có trong catalog thì vắng trong kết quả. */
public interface CatalogTitleClient {

    /** Tối đa 100 id mỗi lần. */
    Map<UUID, String> names(Collection<UUID> ids);
}

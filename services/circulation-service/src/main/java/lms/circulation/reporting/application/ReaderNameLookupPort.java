package lms.circulation.reporting.application;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Tra họ tên người dùng theo lô qua identity-service. Lỗi hoặc id không tra được thì không có trong kết quả. */
public interface ReaderNameLookupPort {

    /** Tối đa {@value #MAX_BATCH} id mỗi lần gọi. */
    int MAX_BATCH = 100;

    Map<UUID, String> names(Collection<UUID> userIds);
}

package lms.circulation.reporting.application;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Tra tên đầu sách và barcode của bản sao theo lô qua catalog-service. Lỗi hoặc id không tra được thì không có trong kết quả. */
public interface CopyLabelLookupPort {

    /** Tối đa {@value #MAX_BATCH} id mỗi lần gọi. */
    int MAX_BATCH = 100;

    Map<UUID, CopyLabel> labels(Collection<UUID> bookCopyIds);

    record CopyLabel(String titleName, String barcode) {
    }
}

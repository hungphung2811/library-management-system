package lms.tenant.export;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Cổng đọc một bảng của tenant theo luồng (keyset theo lô, không nạp cả bảng). Mỗi dòng trao cho {@link RowConsumer}
 * dưới dạng chuỗi đã định dạng theo hợp đồng ADR-0031 (null là {@code null}).
 */
public interface TenantTableReader {

    /** Trả về số dòng đã đọc. Gọi lại {@code sink} theo thứ tự khóa của bảng. */
    long read(ExportTable table, UUID tenantId, int batchSize, RowConsumer sink) throws IOException;

    @FunctionalInterface
    interface RowConsumer {
        void accept(List<String> row) throws IOException;
    }
}

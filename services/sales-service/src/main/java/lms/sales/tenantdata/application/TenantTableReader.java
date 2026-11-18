package lms.sales.tenantdata.application;

import java.util.List;
import java.util.UUID;
import lms.sales.tenantdata.domain.ExportTable;

/**
 * Cổng đọc dữ liệu của MỘT tenant theo con trỏ (keyset): mỗi lần trả tối đa {@code limit} dòng có khóa lớn hơn
 * {@code afterKey}, sắp theo khóa. Mỗi dòng là mảng giá trị theo thứ tự {@link ExportTable#columns()}: UUID, String,
 * Long, Boolean, Instant, LocalDate hoặc null. Không bao giờ trả dòng của tenant khác.
 */
public interface TenantTableReader {

    /**
     * @param afterKey khóa của dòng cuối trang trước (UUID hoặc String theo kiểu cột khóa), null cho trang đầu
     */
    List<Object[]> readBatch(UUID tenantId, ExportTable table, Object afterKey, int limit);
}

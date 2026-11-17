package lms.sales.shared.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Đọc nhật ký bán hàng (nguồn chuẩn sales_audit_log; màn audit chung ở support có thể thiếu khi Kafka lỗi). */
public class SalesAuditQueryUseCase {

    private final SalesAuditQueryPort port;

    public SalesAuditQueryUseCase(SalesAuditQueryPort port) {
        this.port = port;
    }

    @Transactional(readOnly = true)
    public PageResult<SalesAuditQueryPort.Entry> search(UUID tenantId, String action, String aggregateType,
                                                        UUID aggregateId, Instant from, Instant to, int page,
                                                        int size) {
        return port.search(tenantId, new SalesAuditQueryPort.Criteria(blankToNull(action), blankToNull(aggregateType),
                aggregateId, from, to), page, size);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

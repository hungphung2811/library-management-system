package lms.sales.ordering.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.shared.application.SalesAuditPort;
import org.springframework.transaction.annotation.Transactional;

/**
 * Một lô ẩn danh (một transaction): chọn tối đa {@code limit} đơn đến hạn, xóa tên/điện thoại khách vãng lai, ghi một
 * dòng audit WALK_IN_PII_ANONYMIZED (số đơn, mốc cắt; KHÔNG chứa dữ liệu cá nhân, actor là hệ thống).
 */
public class AnonymizeWalkInBatchUseCase {

    private final WalkInAnonymizationPort port;
    private final SalesAuditPort audit;

    public AnonymizeWalkInBatchUseCase(WalkInAnonymizationPort port, SalesAuditPort audit) {
        this.port = port;
        this.audit = audit;
    }

    @Transactional
    public Result executeBatch(UUID tenantId, Instant cutoff, int limit, Instant now) {
        List<UUID> due = port.findDue(tenantId, cutoff, limit);
        if (due.isEmpty()) {
            return new Result(0, 0);
        }
        int changed = port.anonymize(tenantId, due, now);
        if (changed > 0) {
            audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.WALK_IN_PII_ANONYMIZED,
                    "SalesOrder", null, null, now, Map.of("orders", changed, "cutoff", cutoff.toString())));
        }
        return new Result(due.size(), changed);
    }

    public record Result(int selected, int changed) {
    }
}

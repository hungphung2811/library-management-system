package lms.sales.contact.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.sales.shared.application.SalesAuditPort;
import org.springframework.transaction.annotation.Transactional;

/**
 * Một lô ẩn danh liên hệ hết hạn (một transaction): chọn tối đa {@code limit} dòng, ẩn danh bằng một UPDATE có điều
 * kiện, ghi một audit SHOP_CONTACT_PII_ANONYMIZED (số dòng, mốc cắt; không PII, actor là hệ thống).
 */
public class AnonymizeShopContactBatchUseCase {

    private final ShopContactRepository repository;
    private final SalesAuditPort audit;

    public AnonymizeShopContactBatchUseCase(ShopContactRepository repository, SalesAuditPort audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional
    public Result executeBatch(UUID tenantId, Instant cutoff, int limit, Instant now) {
        List<UUID> due = repository.findDueForAnonymization(tenantId, cutoff, limit);
        if (due.isEmpty()) {
            return new Result(0, 0);
        }
        int changed = repository.anonymizeAll(tenantId, due, now);
        if (changed > 0) {
            audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.SHOP_CONTACT_PII_ANONYMIZED,
                    "ShopContactRequest", null, null, now, Map.of("requests", changed, "cutoff", cutoff.toString())));
        }
        return new Result(due.size(), changed);
    }

    public record Result(int selected, int changed) {
    }
}

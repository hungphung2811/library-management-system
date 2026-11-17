package lms.sales.contact.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lms.sales.contact.domain.ContactStatus;
import lms.sales.contact.domain.ShopContactRequest;
import lms.sales.shared.application.SalesAuditPort;
import org.springframework.transaction.annotation.Transactional;

/** Nhân viên đổi trạng thái (R6, khóa lạc quan bằng version) và ẩn danh ngay (R8). Audit không PII. */
public class ShopContactWorkflowUseCases {

    private final ShopContactRepository repository;
    private final SalesAuditPort audit;
    private final Clock clock;

    public ShopContactWorkflowUseCases(ShopContactRepository repository, SalesAuditPort audit, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.clock = clock;
    }

    /** version lệch hoặc chuyển trạng thái sai: 409 SHOP_CONTACT_STATE_CONFLICT. */
    @Transactional
    public ShopContactRequest changeStatus(UUID tenantId, UUID actorId, UUID id, ContactStatus to, long version) {
        ShopContactRequest current = repository.findById(tenantId, id).orElseThrow(ShopContactRequest::notFound);
        if (current.version() != version) {
            throw ShopContactRequest.stateConflict("Yêu cầu liên hệ đã bị thay đổi bởi thao tác khác");
        }
        Instant now = clock.instant();
        ShopContactRequest saved = repository.update(current.changeStatus(to, now));
        audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.SHOP_CONTACT_STATUS_CHANGED,
                "ShopContactRequest", id, actorId, now, Map.of("from", current.status().name(), "to", to.name())));
        return saved;
    }

    /** Đã ẩn danh thì no-op (không ghi, không audit). */
    @Transactional
    public ShopContactRequest anonymize(UUID tenantId, UUID actorId, UUID id) {
        ShopContactRequest current = repository.findById(tenantId, id).orElseThrow(ShopContactRequest::notFound);
        if (current.isAnonymized()) {
            return current;
        }
        Instant now = clock.instant();
        ShopContactRequest saved = repository.update(current.anonymize(now));
        audit.record(new SalesAuditPort.AuditEntry(tenantId, SalesAuditPort.Actions.SHOP_CONTACT_ANONYMIZED,
                "ShopContactRequest", id, actorId, now, Map.of()));
        return saved;
    }
}

package lms.catalog.inventory.domain;

import java.time.Instant;
import java.util.UUID;
import lms.common.kernel.DomainEvent;

/**
 * Một ấn bản vừa đổi tác phẩm (tách hoặc gộp, ADR-0026 3.2), phát SAU commit trên topic inventory.events (best-effort,
 * ADR-0001). Consumer: circulation (cập nhật hàng đợi đặt trước theo tác phẩm). Idempotent theo (editionId, newWorkId).
 */
public record EditionWorkChangedEvent(UUID eventId, UUID editionId, UUID oldWorkId, UUID newWorkId, Instant occurredAt)
        implements DomainEvent {

    @Override
    public String aggregateId() {
        return editionId.toString();
    }
}

package lms.circulation.fine.domain;

import lms.common.kernel.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Khoản phạt được miễn (kind WAIVE) hoặc giảm (kind REDUCE) sau khi được duyệt. Consumer: audit, thông báo độc giả. */
public record FineWaivedEvent(UUID eventId, UUID fineId, UUID readerId, String kind, BigDecimal originalAmount,
                              BigDecimal payableAmount, UUID requestedBy, UUID approvedBy, Instant occurredAt)
        implements DomainEvent {

    @Override
    public String aggregateId() {
        return fineId.toString();
    }
}

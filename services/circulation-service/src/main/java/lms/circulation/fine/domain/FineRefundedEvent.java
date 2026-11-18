package lms.circulation.fine.domain;

import lms.common.kernel.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Đã duyệt hoàn khoản đền bù mất (tìm lại sách). Chỉ là bút toán ghi nhận, không chuyển tiền thật. Consumer: audit, thông báo. */
public record FineRefundedEvent(UUID eventId, UUID refundId, UUID fineId, UUID readerId, UUID bookCopyId,
                                BigDecimal refundAmount, String refundMethod, UUID requestedBy, UUID approvedBy,
                                Instant occurredAt) implements DomainEvent {

    @Override
    public String aggregateId() {
        return fineId.toString();
    }
}

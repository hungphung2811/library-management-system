package lms.catalog.inventory.domain;

import lms.common.kernel.DomainEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * Một bản sao vừa sẵn sàng cho mượn (nhập kho hoặc được nhả). Consumer: circulation (giữ cho người chờ, QĐ9).
 * {@code workId} là tác phẩm của ấn bản (ADR-0026), null nếu ấn bản chưa gắn tác phẩm.
 * {@code language} là ngôn ngữ của ấn bản, null nếu ấn bản chưa khai ngôn ngữ.
 */
public record CopyAvailableEvent(UUID eventId, UUID copyId, UUID titleId, Instant occurredAt, UUID workId,
                                 String language) implements DomainEvent {

    /** Dạng 4 trường (chưa có workId, ADR-0026): consumer cũ bỏ qua trường mới, event cũ không có workId. */
    public CopyAvailableEvent(UUID eventId, UUID copyId, UUID titleId, Instant occurredAt) {
        this(eventId, copyId, titleId, occurredAt, null, null);
    }

    /** Dạng 5 trường (có workId, chưa có language). */
    public CopyAvailableEvent(UUID eventId, UUID copyId, UUID titleId, Instant occurredAt, UUID workId) {
        this(eventId, copyId, titleId, occurredAt, workId, null);
    }

    @Override
    public String aggregateId() {
        return copyId.toString();
    }
}

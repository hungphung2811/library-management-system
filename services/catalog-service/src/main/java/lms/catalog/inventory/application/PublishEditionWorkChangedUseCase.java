package lms.catalog.inventory.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import lms.catalog.inventory.domain.EditionWorkChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phát {@link EditionWorkChangedEvent} cho từng ấn bản đã đổi tác phẩm. Người gọi (catalog) gọi SAU khi transaction ghi
 * đã commit. Best-effort (ADR-0001): lỗi phát chỉ log, không làm hỏng yêu cầu; một lần gửi chậm hơn 1 giây thì bỏ phần còn lại.
 */
public class PublishEditionWorkChangedUseCase {

    private static final Logger log = LoggerFactory.getLogger(PublishEditionWorkChangedUseCase.class);
    private static final long SLOW_PUBLISH_NANOS = 1_000_000_000L;

    private final PublishInventoryEventPort eventPort;
    private final Clock clock;

    public PublishEditionWorkChangedUseCase(PublishInventoryEventPort eventPort, Clock clock) {
        this.eventPort = eventPort;
        this.clock = clock;
    }

    /** Một ấn bản đổi từ tác phẩm cũ sang tác phẩm mới (oldWorkId null nếu ấn bản trước đó chưa gắn tác phẩm). */
    public record Change(UUID editionId, UUID oldWorkId, UUID newWorkId) {
    }

    /** @return số sự kiện đã thử phát */
    public int execute(Collection<Change> changes) {
        Instant now = Instant.now(clock);
        int attempted = 0;
        for (Change c : changes) {
            long start = System.nanoTime();
            try {
                eventPort.publish(new EditionWorkChangedEvent(UUID.randomUUID(), c.editionId(), c.oldWorkId(),
                        c.newWorkId(), now));
            } catch (RuntimeException e) {
                log.warn("Không phát được EditionWorkChangedEvent cho ấn bản {} (best-effort)", c.editionId(), e);
            }
            attempted++;
            if (System.nanoTime() - start > SLOW_PUBLISH_NANOS) {
                log.warn("Kafka chậm hoặc không khả dụng: bỏ phát {} EditionWorkChangedEvent còn lại",
                        changes.size() - attempted);
                break;
            }
        }
        return attempted;
    }
}

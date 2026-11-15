package lms.support.notification.application;

import java.time.Clock;
import java.util.UUID;
import lms.support.notification.domain.Notification;
import lms.support.notification.domain.NotificationNotFoundException;
import org.springframework.transaction.annotation.Transactional;

/** Đánh dấu một thông báo của chính mình là đã đọc; idempotent, của người khác thì như không tồn tại. */
public class MarkNotificationReadUseCase {

    private final MarkNotificationsPort port;
    private final Clock clock;

    public MarkNotificationReadUseCase(MarkNotificationsPort port, Clock clock) {
        this.port = port;
        this.clock = clock;
    }

    @Transactional(transactionManager = NotificationTransactions.MANAGER)
    public NotificationResponse execute(UUID id, UUID userId) {
        Notification current = port.findOwned(id, userId).orElseThrow(NotificationNotFoundException::new);
        Notification updated = current.markRead(clock.instant());
        if (updated != current) {
            port.markRead(id, userId, updated.readAt());
        }
        return NotificationResponse.from(updated);
    }
}

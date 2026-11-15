package lms.support.notification.application;

import java.time.Clock;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/** Đánh dấu mọi thông báo chưa đọc của chính mình; trả số thông báo vừa đổi (0 nếu không còn gì). */
public class MarkAllNotificationsReadUseCase {

    private final MarkNotificationsPort port;
    private final Clock clock;

    public MarkAllNotificationsReadUseCase(MarkNotificationsPort port, Clock clock) {
        this.port = port;
        this.clock = clock;
    }

    @Transactional(transactionManager = NotificationTransactions.MANAGER)
    public int execute(UUID userId) {
        return port.markAllRead(userId, clock.instant());
    }
}

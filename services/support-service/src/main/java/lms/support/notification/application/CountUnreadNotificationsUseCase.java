package lms.support.notification.application;

import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class CountUnreadNotificationsUseCase {

    private final MarkNotificationsPort port;

    public CountUnreadNotificationsUseCase(MarkNotificationsPort port) {
        this.port = port;
    }

    @Transactional(transactionManager = NotificationTransactions.MANAGER, readOnly = true)
    public long execute(UUID userId) {
        return port.countUnread(userId);
    }
}

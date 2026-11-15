package lms.support.notification.application;

import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class ListMyNotificationsUseCase {

    private final FindNotificationsPort find;

    public ListMyNotificationsUseCase(FindNotificationsPort find) {
        this.find = find;
    }

    @Transactional(transactionManager = NotificationTransactions.MANAGER, readOnly = true)
    public List<NotificationResponse> execute(UUID userId) {
        return find.findByUser(userId).stream().map(NotificationResponse::from).toList();
    }
}

package lms.support.notification.application;

import java.time.Clock;
import lms.support.notification.domain.Notification;
import org.springframework.transaction.annotation.Transactional;

/** Tạo thông báo trong tenant của TenantContext hiện tại; idempotent theo eventId. */
public class CreateNotificationUseCase {

    private final SaveNotificationPort save;
    private final Clock clock;

    public CreateNotificationUseCase(SaveNotificationPort save, Clock clock) {
        this.save = save;
        this.clock = clock;
    }

    /** @return true nếu tạo mới, false nếu event này đã tạo thông báo trước đó. */
    @Transactional(NotificationTransactions.MANAGER)
    public boolean execute(CreateNotificationCommand command) {
        return save.saveIfAbsent(Notification.create(command.eventId(), command.userId(), command.type(),
                command.message(), clock.instant()));
    }
}

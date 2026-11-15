package lms.support.notification.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import lms.support.notification.application.FindNotificationsPort;
import lms.support.notification.application.SaveNotificationPort;
import lms.support.notification.domain.Notification;
import lms.tenant.TenantContext;

/** Adapter JPA cho AUDIT_DB (EntityManagerFactory/TransactionManager lấy từ database-common). */
public class NotificationPersistenceAdapter implements SaveNotificationPort, FindNotificationsPort {

    private final SpringDataNotificationRepository jpa;

    public NotificationPersistenceAdapter(SpringDataNotificationRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean saveIfAbsent(Notification n) {
        return jpa.insertIfAbsent(n.id(), TenantContext.require(), n.eventId(), n.userId(), n.type(), n.message(),
                n.createdAt(), n.readAt()) == 1;
    }

    @Override
    public List<Notification> findByUser(UUID userId) {
        return jpa.findByUserIdOrderByCreatedAtDescIdAsc(userId).stream()
                .map(e -> new Notification(e.getId(), e.getEventId(), e.getUserId(), e.getType(), e.getMessage(),
                        e.getCreatedAt(), e.getReadAt()))
                .toList();
    }
}

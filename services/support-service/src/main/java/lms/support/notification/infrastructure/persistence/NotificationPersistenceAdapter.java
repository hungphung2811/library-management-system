package lms.support.notification.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.support.notification.application.FindNotificationsPort;
import lms.support.notification.application.MarkNotificationsPort;
import lms.support.notification.application.SaveNotificationPort;
import lms.support.notification.domain.Notification;
import lms.tenant.TenantContext;

/** Adapter JPA cho AUDIT_DB (EntityManagerFactory/TransactionManager lấy từ database-common). */
public class NotificationPersistenceAdapter
        implements SaveNotificationPort, FindNotificationsPort, MarkNotificationsPort {

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
        return jpa.findByUserIdOrderByCreatedAtDescIdAsc(userId).stream().map(NotificationPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public Optional<Notification> findOwned(UUID id, UUID userId) {
        return jpa.findByIdAndUserId(id, userId).map(NotificationPersistenceAdapter::toDomain);
    }

    @Override
    public void markRead(UUID id, UUID userId, Instant at) {
        jpa.markRead(id, userId, at);
    }

    @Override
    public int markAllRead(UUID userId, Instant at) {
        return jpa.markAllRead(userId, at);
    }

    @Override
    public long countUnread(UUID userId) {
        return jpa.countByUserIdAndReadAtIsNull(userId);
    }

    private static Notification toDomain(NotificationJpaEntity e) {
        return new Notification(e.getId(), e.getEventId(), e.getUserId(), e.getType(), e.getMessage(),
                e.getCreatedAt(), e.getReadAt());
    }
}

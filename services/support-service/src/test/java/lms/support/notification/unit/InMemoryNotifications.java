package lms.support.notification.unit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.stream.Stream;
import lms.support.notification.application.MarkNotificationsPort;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lms.support.notification.application.FindNotificationsPort;
import lms.support.notification.application.SaveNotificationPort;
import lms.support.notification.domain.Notification;
import lms.tenant.TenantContext;

/** Bản giả của cổng lưu trữ: cách ly theo TenantContext như @TenantId/RLS, idempotent theo (tenant, eventId). */
class InMemoryNotifications implements SaveNotificationPort, FindNotificationsPort, MarkNotificationsPort {

    private static final class Row {
        private final UUID tenantId;
        private Notification notification;

        Row(UUID tenantId, Notification notification) {
            this.tenantId = tenantId;
            this.notification = notification;
        }

        UUID tenantId() { return tenantId; }
        Notification notification() { return notification; }
    }

    private Stream<Row> mine(UUID userId) {
        UUID tenant = TenantContext.require();
        return rows.stream().filter(r -> r.tenantId().equals(tenant) && r.notification().userId().equals(userId));
    }

    @Override
    public Optional<Notification> findOwned(UUID id, UUID userId) {
        return mine(userId).map(Row::notification).filter(n -> n.id().equals(id)).findFirst();
    }

    @Override
    public void markRead(UUID id, UUID userId, Instant at) {
        mine(userId).filter(r -> r.notification.id().equals(id) && !r.notification.isRead())
                .forEach(r -> r.notification = r.notification.markRead(at));
    }

    @Override
    public int markAllRead(UUID userId, Instant at) {
        List<Row> unread = mine(userId).filter(r -> !r.notification.isRead()).toList();
        unread.forEach(r -> r.notification = r.notification.markRead(at));
        return unread.size();
    }

    @Override
    public long countUnread(UUID userId) {
        return mine(userId).filter(r -> !r.notification.isRead()).count();
    }

    private final List<Row> rows = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();

    @Override
    public boolean saveIfAbsent(Notification n) {
        UUID tenant = TenantContext.require();
        if (!keys.add(tenant + ":" + n.eventId())) {
            return false;
        }
        rows.add(new Row(tenant, n));
        return true;
    }

    @Override
    public List<Notification> findByUser(UUID userId) {
        UUID tenant = TenantContext.require();
        return rows.stream().filter(r -> r.tenantId().equals(tenant) && r.notification().userId().equals(userId))
                .map(Row::notification).sorted(Comparator.comparing(Notification::createdAt).reversed()).toList();
    }
}

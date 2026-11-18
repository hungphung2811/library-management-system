package lms.support.notification.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lms.support.notification.domain.NotificationCategory;
import lms.support.notification.domain.NotificationChannel;
import org.hibernate.annotations.TenantId;

/** Người dùng tắt nhận theo nhóm và kênh (không có dòng = nhận). Khóa JPA (user, category, channel), tenant theo phiên. */
@Entity
@Table(name = "notification_preferences")
@IdClass(NotificationPreferenceJpaEntity.Key.class)
class NotificationPreferenceJpaEntity {

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Enumerated(EnumType.STRING)
    private NotificationCategory category;

    @Id
    @Enumerated(EnumType.STRING)
    private NotificationChannel channel;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationPreferenceJpaEntity() {
    }

    NotificationPreferenceJpaEntity(UUID userId, NotificationCategory category, NotificationChannel channel,
                                    boolean enabled, Instant at) {
        this.userId = userId;
        this.category = category;
        this.channel = channel;
        update(enabled, at);
    }

    void update(boolean enabled, Instant at) {
        this.enabled = enabled;
        this.updatedAt = at;
    }

    NotificationCategory getCategory() { return category; }
    NotificationChannel getChannel() { return channel; }
    boolean isEnabled() { return enabled; }

    static class Key implements Serializable {
        private UUID userId;
        private NotificationCategory category;
        private NotificationChannel channel;

        protected Key() {
        }

        Key(UUID userId, NotificationCategory category, NotificationChannel channel) {
            this.userId = userId;
            this.category = category;
            this.channel = channel;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(userId, k.userId) && category == k.category
                    && channel == k.channel;
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, category, channel);
        }
    }
}

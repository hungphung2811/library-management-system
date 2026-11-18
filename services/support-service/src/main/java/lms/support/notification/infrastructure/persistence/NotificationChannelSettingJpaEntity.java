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
import lms.support.notification.domain.NotificationChannel;
import lms.support.notification.domain.NotificationType;
import org.hibernate.annotations.TenantId;

/**
 * Bật/tắt kênh theo loại thông báo của tenant. Khóa chính ở DB là (tenant_id, notification_type, channel); ở JPA chỉ
 * cần (type, channel) vì mỗi phiên Hibernate gắn một tenant (@TenantId lọc và điền tenant_id, RLS lọc thêm).
 */
@Entity
@Table(name = "notification_channel_settings")
@IdClass(NotificationChannelSettingJpaEntity.Key.class)
class NotificationChannelSettingJpaEntity {

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type")
    private NotificationType type;

    @Id
    @Enumerated(EnumType.STRING)
    private NotificationChannel channel;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationChannelSettingJpaEntity() {
    }

    NotificationChannelSettingJpaEntity(NotificationType type, NotificationChannel channel, boolean enabled, UUID by,
                                        Instant at) {
        this.type = type;
        this.channel = channel;
        update(enabled, by, at);
    }

    void update(boolean enabled, UUID by, Instant at) {
        this.enabled = enabled;
        this.updatedBy = by;
        this.updatedAt = at;
    }

    NotificationType getType() { return type; }
    NotificationChannel getChannel() { return channel; }
    boolean isEnabled() { return enabled; }

    static class Key implements Serializable {
        private NotificationType type;
        private NotificationChannel channel;

        protected Key() {
        }

        Key(NotificationType type, NotificationChannel channel) {
            this.type = type;
            this.channel = channel;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && type == k.type && channel == k.channel;
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, channel);
        }
    }
}

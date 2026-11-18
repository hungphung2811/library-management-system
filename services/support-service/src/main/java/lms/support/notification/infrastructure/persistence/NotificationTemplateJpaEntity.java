package lms.support.notification.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lms.support.notification.domain.NotificationChannel;
import lms.support.notification.domain.NotificationType;
import org.hibernate.annotations.TenantId;

/** Mẫu nội dung ghi đè của tenant (mỗi lần lưu một phiên bản mới, đúng một phiên bản active). */
@Entity
@Table(name = "notification_templates")
class NotificationTemplateJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, updatable = false)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private NotificationChannel channel;

    @Column(nullable = false, updatable = false)
    private int version;

    @Column(nullable = false, updatable = false)
    private String subject;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String body;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NotificationTemplateJpaEntity() {
    }

    NotificationTemplateJpaEntity(UUID id, NotificationType type, NotificationChannel channel, int version,
                                  String subject, String body, UUID createdBy, Instant createdAt) {
        this.id = id;
        this.type = type;
        this.channel = channel;
        this.version = version;
        this.subject = subject;
        this.body = body;
        this.active = true;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    NotificationType getType() { return type; }
    NotificationChannel getChannel() { return channel; }
    int getVersion() { return version; }
    String getSubject() { return subject; }
    String getBody() { return body; }
    UUID getCreatedBy() { return createdBy; }
    Instant getCreatedAt() { return createdAt; }
}

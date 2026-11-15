package lms.support.notification.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** Thông báo thuộc một tenant: tenant_id do Hibernate lọc theo TenantContext (@TenantId), RLS lọc thêm ở DB. */
@Entity
@Table(name = "notifications")
class NotificationJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected NotificationJpaEntity() {
    }

    UUID getId() { return id; }
    UUID getEventId() { return eventId; }
    UUID getUserId() { return userId; }
    String getType() { return type; }
    String getMessage() { return message; }
    Instant getCreatedAt() { return createdAt; }
    Instant getReadAt() { return readAt; }
}

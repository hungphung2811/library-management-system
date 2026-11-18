package lms.support.notification.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lms.support.notification.domain.Delivery;
import lms.support.notification.domain.DeliveryStatus;
import lms.support.notification.domain.NotificationChannel;
import lms.support.notification.domain.NotificationType;
import lms.support.notification.domain.SkipReason;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * Lượt gửi thông báo của một tenant: tenant_id do Hibernate lọc theo TenantContext (@TenantId), RLS lọc thêm ở DB.
 * Dòng mới chèn bằng native INSERT ... ON CONFLICT DO NOTHING (khử trùng); entity này dùng để đọc và cập nhật.
 */
@Entity
@Table(name = "notification_deliveries")
class NotificationDeliveryJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, updatable = false)
    private NotificationType type;

    @Column(nullable = false, updatable = false)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private NotificationChannel channel;

    @Column(name = "recipient_user_id", updatable = false)
    private UUID recipientUserId;

    @Column(name = "recipient_key", nullable = false, updatable = false)
    private String recipientKey;

    @Column(name = "recipient_address")
    private String recipientAddress;

    @Column(name = "recipient_address_masked")
    private String recipientAddressMasked;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false, columnDefinition = "jsonb")
    private Map<String, String> params = new HashMap<>();

    @Column(name = "template_source")
    private String templateSource;

    @Column(name = "template_version")
    private Integer templateVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "skip_reason")
    private SkipReason skipReason;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "attempts_base", nullable = false)
    private int attemptsBase;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error_code")
    private String lastErrorCode;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "provider_message_id")
    private String providerMessageId;

    @Column(name = "last_retry_by")
    private UUID lastRetryBy;

    @Column(name = "last_retry_at")
    private Instant lastRetryAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationDeliveryJpaEntity() {
    }

    /** Chép các trường có thể đổi từ domain (các trường định danh là bất biến). */
    void apply(Delivery d) {
        this.recipientAddress = d.recipientAddress();
        this.recipientAddressMasked = d.recipientAddressMasked();
        this.templateSource = d.templateSource();
        this.templateVersion = d.templateVersion();
        this.status = d.status();
        this.skipReason = d.skipReason();
        this.attempts = d.attempts();
        this.attemptsBase = d.attemptsBase();
        this.nextAttemptAt = d.nextAttemptAt();
        this.lastErrorCode = d.lastErrorCode();
        this.lastErrorAt = d.lastErrorAt();
        this.sentAt = d.sentAt();
        this.providerMessageId = d.providerMessageId();
        this.lastRetryBy = d.lastRetryBy();
        this.lastRetryAt = d.lastRetryAt();
        this.updatedAt = d.updatedAt();
    }

    Delivery toDomain() {
        return new Delivery(id, eventId, eventType, type, channel, recipientUserId, recipientKey, recipientAddress,
                recipientAddressMasked, params, templateSource, templateVersion, status, skipReason, attempts,
                attemptsBase, nextAttemptAt, lastErrorCode, lastErrorAt, sentAt, providerMessageId, lastRetryBy,
                lastRetryAt, createdAt, updatedAt);
    }
}

package lms.support.notification.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Thông báo gửi tới một người dùng trong tenant hiện tại. eventId là khóa idempotency: event giao lại
 * (Kafka at-least-once) chỉ tạo đúng một thông báo.
 */
public record Notification(UUID id, UUID eventId, UUID userId, String type, String message, Instant createdAt,
                           Instant readAt) {

    public Notification {
        if (id == null) throw new InvalidNotificationException("id không được null");
        if (eventId == null) throw new InvalidNotificationException("eventId không được null");
        if (userId == null) throw new InvalidNotificationException("userId không được null");
        if (type == null || type.isBlank()) throw new InvalidNotificationException("type không được trống");
        if (message == null || message.isBlank()) throw new InvalidNotificationException("message không được trống");
        if (createdAt == null) throw new InvalidNotificationException("createdAt không được null");
    }

    public static Notification create(UUID eventId, UUID userId, String type, String message, Instant now) {
        return new Notification(UUID.randomUUID(), eventId, userId, type, message, now, null);
    }
}

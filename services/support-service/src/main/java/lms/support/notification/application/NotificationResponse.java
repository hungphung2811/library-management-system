package lms.support.notification.application;

import java.time.Instant;
import java.util.UUID;
import lms.support.notification.domain.Notification;

public record NotificationResponse(UUID id, String type, String message, Instant createdAt, Instant readAt) {

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.id(), n.type(), n.message(), n.createdAt(), n.readAt());
    }
}

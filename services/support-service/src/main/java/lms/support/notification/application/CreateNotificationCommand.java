package lms.support.notification.application;

import java.util.UUID;

public record CreateNotificationCommand(UUID eventId, UUID userId, String type, String message) {
}

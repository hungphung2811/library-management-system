package lms.api.support.dto;

import java.time.Instant;
import java.util.UUID;

public record NotificationView(UUID id, String type, String message, Instant createdAt, Instant readAt) {
}

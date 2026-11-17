package lms.api.support.dto;

import java.time.Instant;
import java.util.UUID;

public record AuditLogView(UUID id, UUID eventId, String eventType, String aggregateId, Instant occurredAt,
                           Instant recordedAt, String payload, UUID tenantId) {
}

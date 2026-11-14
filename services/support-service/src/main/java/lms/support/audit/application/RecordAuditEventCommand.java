package lms.support.audit.application;

import java.time.Instant;
import java.util.UUID;

public record RecordAuditEventCommand(UUID eventId, String eventType, String aggregateId, Instant occurredAt,
                                      String payload) {
}

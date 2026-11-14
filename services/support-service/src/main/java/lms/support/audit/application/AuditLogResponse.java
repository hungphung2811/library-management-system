package lms.support.audit.application;

import java.time.Instant;
import java.util.UUID;
import lms.support.audit.domain.AuditLog;

public record AuditLogResponse(UUID id, UUID eventId, String eventType, String aggregateId, Instant occurredAt,
                               Instant recordedAt, String payload) {

    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(log.id(), log.eventId(), log.eventType(), log.aggregateId(),
                log.occurredAt(), log.recordedAt(), log.payload());
    }
}

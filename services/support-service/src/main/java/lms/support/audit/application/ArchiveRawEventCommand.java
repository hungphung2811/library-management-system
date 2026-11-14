package lms.support.audit.application;

import java.time.Instant;
import java.util.UUID;

/** Envelope thô của một event, lưu nguyên văn để phục vụ điều tra. */
public record ArchiveRawEventCommand(UUID eventId, String topic, String eventType, String aggregateId,
                                     Instant receivedAt, String rawEnvelope) {
}

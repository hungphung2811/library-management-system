package lms.support.audit.application;

import java.time.Instant;
import java.util.UUID;

/** Một event nhận từ Kafka: phần chuẩn hóa để ghi audit và nguyên văn để lưu trữ thô. */
public record IngestAuditEventCommand(UUID eventId, String topic, String eventType, String aggregateId,
                                      Instant occurredAt, String payload, String rawEnvelope) {
}

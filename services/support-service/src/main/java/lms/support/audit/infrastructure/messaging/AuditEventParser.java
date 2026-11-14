package lms.support.audit.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import lms.messaging.EventEnvelope;
import lms.support.audit.application.IngestAuditEventCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Chuyển message Kafka (EventEnvelope JSON) thành command. Message hỏng cho ra Optional rỗng, không ném lỗi. */
public class AuditEventParser {

    private static final Logger log = LoggerFactory.getLogger(AuditEventParser.class);

    private final ObjectMapper objectMapper;

    public AuditEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<IngestAuditEventCommand> parse(String topic, String message) {
        if (message == null || message.isBlank()) {
            log.warn("Bỏ qua message rỗng từ topic {}", topic);
            return Optional.empty();
        }
        try {
            EventEnvelope envelope = objectMapper.readValue(message, EventEnvelope.class);
            if (envelope.eventId() == null || isBlank(envelope.eventType()) || isBlank(envelope.aggregateId())
                    || envelope.occurredAt() == null) {
                log.warn("Bỏ qua message thiếu eventId/eventType/aggregateId/occurredAt từ topic {}", topic);
                return Optional.empty();
            }
            String payload = objectMapper.writeValueAsString(envelope.payload() == null ? java.util.Map.of() : envelope.payload());
            return Optional.of(new IngestAuditEventCommand(envelope.eventId(), topic, envelope.eventType(),
                    envelope.aggregateId(), envelope.occurredAt(), payload, message));
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("Bỏ qua message không đọc được từ topic {}: {}", topic, e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

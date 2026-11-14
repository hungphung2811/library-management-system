package lms.messaging;

import lms.common.kernel.DomainEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/** Publish domain event lên một topic dưới dạng EventEnvelope JSON, key theo aggregateId. */
public class KafkaEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper, String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    /**
     * Best-effort: lỗi gửi (broker không với tới, hết thời gian chờ metadata...) chỉ được log, không làm hỏng
     * request đang xử lý. Hệ quả là event có thể bị mất, đây là nợ kỹ thuật đã ghi ở ADR-0001; cách khắc phục
     * đúng là Transactional Outbox. Lỗi serialize là lỗi lập trình nên vẫn ném ra.
     */
    public void publish(DomainEvent event) {
        String json;
        try {
            json = objectMapper.writeValueAsString(EventEnvelope.of(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không serialize được event " + event.eventType(), e);
        }
        try {
            kafkaTemplate.send(topic, event.aggregateId(), json).whenComplete((result, error) -> {
                if (error != null) {
                    log.error("Publish {} thất bại, eventId={}", event.eventType(), event.eventId(), error);
                }
            });
        } catch (RuntimeException e) {
            log.error("Không gửi được {} lên topic {}, eventId={}", event.eventType(), topic, event.eventId(), e);
        }
    }
}

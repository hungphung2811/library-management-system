package lms.messaging;

import lms.common.kernel.DomainEvent;
import lms.tenant.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
            json = objectMapper.writeValueAsString(EventEnvelope.of(event, TenantContext.current().orElse(null)));
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

    /**
     * Gửi đồng bộ: chờ broker xác nhận tối đa {@code timeout}, ném {@link EventPublishException} khi lỗi/hết giờ/bị ngắt.
     * Dành cho job cần "phát được mới commit" (ADR-0030 D11); không thay thế Outbox: nếu commit lỗi sau khi đã phát,
     * sự kiện có thể trùng nên consumer phải khử trùng theo eventId. {@link #publish} vẫn best-effort như cũ.
     */
    public void publishAndWait(DomainEvent event, Duration timeout) {
        String json;
        try {
            json = objectMapper.writeValueAsString(EventEnvelope.of(event, TenantContext.current().orElse(null)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không serialize được event " + event.eventType(), e);
        }
        try {
            kafkaTemplate.send(topic, event.aggregateId(), json).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishException("Bị ngắt khi gửi " + event.eventType() + " lên topic " + topic, e);
        } catch (ExecutionException e) {
            throw new EventPublishException("Gửi " + event.eventType() + " lên topic " + topic + " thất bại", e.getCause());
        } catch (TimeoutException e) {
            throw new EventPublishException("Hết " + timeout.toMillis() + "ms chờ gửi " + event.eventType()
                    + " lên topic " + topic, e);
        } catch (RuntimeException e) {
            throw new EventPublishException("Không gửi được " + event.eventType() + " lên topic " + topic, e);
        }
    }

    /** Gửi đồng bộ thất bại (broker lỗi, hết giờ, bị ngắt). */
    public static class EventPublishException extends RuntimeException {
        public EventPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

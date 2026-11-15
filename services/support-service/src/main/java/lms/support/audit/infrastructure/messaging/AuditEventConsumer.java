package lms.support.audit.infrastructure.messaging;

import lms.messaging.TenantAwareEventHandler;
import lms.support.audit.application.IngestAuditEventUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Nghe event của các service khác để ghi audit. Xử lý idempotent theo eventId nên giao lại không sao.
 * Message hỏng chỉ bị log và bỏ qua; lỗi hạ tầng tạm thời (ví dụ Postgres) được ném ra để Kafka giao lại.
 */
@Component
public class AuditEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuditEventConsumer.class);

    private final AuditEventParser parser;
    private final IngestAuditEventUseCase ingest;

    public AuditEventConsumer(AuditEventParser parser, IngestAuditEventUseCase ingest) {
        this.parser = parser;
        this.ingest = ingest;
    }

    @KafkaListener(topics = {"${app.kafka.topics.auth-events}", "${app.kafka.topics.access-events}",
            "${app.kafka.topics.borrowing-events}"}, groupId = "${spring.kafka.consumer.group-id:support-service}")
    public void onEvent(String message, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        parser.parse(topic, message).ifPresent(command ->
                TenantAwareEventHandler.handle(command.tenantId(), command.eventType(), command.eventId(), () -> {
                    boolean recorded = ingest.execute(command);
                    log.debug("Audit {} từ {} (eventId={}): {}", command.eventType(), topic, command.eventId(),
                            recorded ? "đã ghi" : "bỏ qua vì đã có");
                }));
    }
}

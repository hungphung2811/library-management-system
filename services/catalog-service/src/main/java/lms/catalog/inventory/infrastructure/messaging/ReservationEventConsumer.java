package lms.catalog.inventory.infrastructure.messaging;

import lms.catalog.inventory.application.HoldCopyUseCase;
import lms.catalog.inventory.application.ReleaseCopyUseCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lms.messaging.TenantAwareEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Nghe event của circulation-service: ReservationReadyEvent thì giữ bản sao (RESERVED), CopyReleasedEvent thì trả
 * về kho (AVAILABLE). Message hỏng chỉ bị log và bỏ qua; lỗi tạm thời được ném ra để Kafka giao lại (use case idempotent).
 */
@Component
public class ReservationEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReservationEventConsumer.class);

    private final ObjectMapper mapper;
    private final HoldCopyUseCase holdCopy;
    private final ReleaseCopyUseCase releaseCopy;

    public ReservationEventConsumer(ObjectMapper mapper, HoldCopyUseCase holdCopy, ReleaseCopyUseCase releaseCopy) {
        this.mapper = mapper;
        this.holdCopy = holdCopy;
        this.releaseCopy = releaseCopy;
    }

    @KafkaListener(topics = "${app.kafka.topics.reservation-events}",
            groupId = "${spring.kafka.consumer.group-id:catalog-service}")
    public void onEvent(String message) {
        String type;
        UUID copyId;
        JsonNode root;
        try {
            root = mapper.readTree(message);
            type = root.path("eventType").asText();
            if (!"ReservationReadyEvent".equals(type) && !"CopyReleasedEvent".equals(type)) {
                return;
            }
            copyId = UUID.fromString(root.path("payload").path("copyId").asText());
        } catch (Exception e) {
            log.warn("Bỏ qua message reservation.events không hợp lệ: {}", e.toString());
            return;
        }
        TenantAwareEventHandler.handle(root, () -> {
            if ("ReservationReadyEvent".equals(type)) {
                holdCopy.execute(copyId);
            } else {
                releaseCopy.execute(copyId);
            }
        });
    }
}

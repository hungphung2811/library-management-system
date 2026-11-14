package lms.circulation.reservation.infrastructure.messaging;

import lms.circulation.reservation.application.HoldCopyForNextReservationUseCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Nghe event của catalog-service. CopyAvailableEvent (bản sao mới nhập, sửa xong hoặc vừa nhả) thì giữ ngay cho
 * người đầu hàng đợi (QĐ9). Message hỏng chỉ bị log và bỏ qua; lỗi tạm thời (DB) được ném ra để Kafka giao lại,
 * và use case idempotent nên giao lại không sao.
 */
@Component
public class InventoryEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventConsumer.class);

    private final ObjectMapper mapper;
    private final HoldCopyForNextReservationUseCase holdCopy;

    public InventoryEventConsumer(ObjectMapper mapper, HoldCopyForNextReservationUseCase holdCopy) {
        this.mapper = mapper;
        this.holdCopy = holdCopy;
    }

    @KafkaListener(topics = "${app.kafka.topics.inventory-events}",
            groupId = "${spring.kafka.consumer.group-id:circulation-service}")
    public void onEvent(String message) {
        UUID copyId;
        UUID titleId;
        try {
            JsonNode root = mapper.readTree(message);
            if (!"CopyAvailableEvent".equals(root.path("eventType").asText())) {
                return;
            }
            JsonNode payload = root.path("payload");
            copyId = UUID.fromString(payload.path("copyId").asText());
            titleId = UUID.fromString(payload.path("titleId").asText());
        } catch (Exception e) {
            log.warn("Bỏ qua message inventory.events không hợp lệ: {}", e.toString());
            return;
        }
        boolean held = holdCopy.holdIfAnyoneWaiting(titleId, copyId);
        log.debug("CopyAvailable copy={} title={}: {}", copyId, titleId, held ? "đã giữ cho người chờ" : "không có người chờ");
    }
}

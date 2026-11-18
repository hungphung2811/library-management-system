package lms.circulation.reservation.infrastructure.messaging;

import lms.circulation.reservation.application.HoldCopyForNextReservationUseCase;
import lms.circulation.reservation.application.MoveReservationsToWorkUseCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lms.messaging.TenantAwareEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Nghe event của catalog-service. CopyAvailableEvent (bản sao mới nhập, sửa xong hoặc vừa nhả) thì giữ ngay cho
 * người đầu hàng đợi của tác phẩm cùng ngôn ngữ (QĐ9, ADR-0026; event cũ không có workId/language rơi về hàng đợi theo
 * ấn bản). EditionWorkChangedEvent (ấn bản đổi tác phẩm) thì chuyển đặt trước của ấn bản sang tác phẩm mới. Message hỏng
 * chỉ bị log và bỏ qua; lỗi tạm thời (DB) được ném ra để Kafka giao lại, và use case idempotent nên giao lại không sao.
 */
@Component
public class InventoryEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventConsumer.class);

    private final ObjectMapper mapper;
    private final HoldCopyForNextReservationUseCase holdCopy;
    private final MoveReservationsToWorkUseCase moveToWork;

    public InventoryEventConsumer(ObjectMapper mapper, HoldCopyForNextReservationUseCase holdCopy,
                                  MoveReservationsToWorkUseCase moveToWork) {
        this.mapper = mapper;
        this.holdCopy = holdCopy;
        this.moveToWork = moveToWork;
    }

    @KafkaListener(topics = "${app.kafka.topics.inventory-events}",
            groupId = "${spring.kafka.consumer.group-id:circulation-service}")
    public void onEvent(String message) {
        JsonNode root;
        String type;
        try {
            root = mapper.readTree(message);
            type = root.path("eventType").asText();
        } catch (Exception e) {
            log.warn("Bỏ qua message inventory.events không hợp lệ: {}", e.toString());
            return;
        }
        if ("CopyAvailableEvent".equals(type)) {
            onCopyAvailable(root);
        } else if ("EditionWorkChangedEvent".equals(type)) {
            onEditionWorkChanged(root);
        }
    }

    private void onCopyAvailable(JsonNode root) {
        UUID copyId;
        UUID titleId;
        UUID workId;
        String language;
        try {
            JsonNode payload = root.path("payload");
            copyId = UUID.fromString(payload.path("copyId").asText());
            titleId = UUID.fromString(payload.path("titleId").asText());
            workId = optionalUuid(payload.path("workId"));
            language = payload.path("language").isTextual() ? payload.path("language").asText() : null;
        } catch (Exception e) {
            log.warn("Bỏ qua CopyAvailableEvent không hợp lệ: {}", e.toString());
            return;
        }
        TenantAwareEventHandler.handle(root, () -> {
            boolean held = holdCopy.holdIfAnyoneWaiting(workId, language, titleId, copyId);
            log.debug("CopyAvailable copy={} title={} work={}: {}", copyId, titleId, workId,
                    held ? "đã giữ cho người chờ" : "không có người chờ");
        });
    }

    private void onEditionWorkChanged(JsonNode root) {
        UUID editionId;
        UUID newWorkId;
        try {
            JsonNode payload = root.path("payload");
            editionId = UUID.fromString(payload.path("editionId").asText());
            newWorkId = UUID.fromString(payload.path("newWorkId").asText());
        } catch (Exception e) {
            log.warn("Bỏ qua EditionWorkChangedEvent không hợp lệ: {}", e.toString());
            return;
        }
        TenantAwareEventHandler.handle(root, () -> {
            int moved = moveToWork.execute(editionId, newWorkId);
            log.debug("EditionWorkChanged edition={} -> work={}: chuyển {} đặt trước", editionId, newWorkId, moved);
        });
    }

    private static UUID optionalUuid(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? UUID.fromString(node.asText()) : null;
    }
}

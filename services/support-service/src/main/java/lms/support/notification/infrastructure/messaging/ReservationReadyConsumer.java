package lms.support.notification.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lms.messaging.TenantAwareEventHandler;
import lms.support.notification.application.CreateNotificationCommand;
import lms.support.notification.application.CreateNotificationUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Nghe reservation.events: ReservationReadyEvent thì tạo thông báo cho độc giả (readerId) trong tenant của event.
 * Idempotent theo eventId; event thiếu tenantId bị bỏ qua (TenantAwareEventHandler); message hỏng chỉ log.
 */
@Component
public class ReservationReadyConsumer {

    static final String EVENT_TYPE = "ReservationReadyEvent";
    static final String NOTIFICATION_TYPE = "RESERVATION_READY";

    private static final Logger log = LoggerFactory.getLogger(ReservationReadyConsumer.class);

    private final ObjectMapper mapper;
    private final CreateNotificationUseCase create;

    public ReservationReadyConsumer(ObjectMapper mapper, CreateNotificationUseCase create) {
        this.mapper = mapper;
        this.create = create;
    }

    @KafkaListener(topics = "${app.kafka.topics.reservation-events}",
            groupId = "${spring.kafka.consumer.group-id:support-service}-notification")
    public void onEvent(String message) {
        JsonNode root;
        UUID eventId;
        UUID readerId;
        String holdUntil;
        try {
            root = mapper.readTree(message);
            if (!EVENT_TYPE.equals(root.path("eventType").asText())) {
                return;
            }
            eventId = UUID.fromString(root.path("eventId").asText());
            readerId = UUID.fromString(root.path("payload").path("readerId").asText());
            holdUntil = root.path("payload").path("holdUntil").asText("");
        } catch (Exception e) {
            log.warn("Bỏ qua message reservation.events không hợp lệ: {}", e.toString());
            return;
        }
        String text = holdUntil.isBlank() ? "Sách bạn đặt trước đã sẵn sàng để nhận."
                : "Sách bạn đặt trước đã sẵn sàng để nhận, được giữ đến " + holdUntil + ".";
        TenantAwareEventHandler.handle(root, () -> {
            boolean created = create.execute(new CreateNotificationCommand(eventId, readerId, NOTIFICATION_TYPE, text));
            log.debug("Thông báo đặt trước sẵn sàng (eventId={}): {}", eventId, created ? "đã tạo" : "bỏ qua vì đã có");
        });
    }
}

package lms.support.notification.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lms.messaging.TenantAwareEventHandler;
import lms.support.notification.application.EnqueueNotificationUseCase;
import lms.support.notification.application.NotificationTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Nghe reservation.events: ReservationReadyEvent thì tạo thông báo trong ứng dụng cho độc giả (readerId) và xếp email
 * (ADR-0030) qua {@link EnqueueNotificationUseCase}, trong tenant của event. Idempotent theo eventId và khóa khử
 * trùng của delivery; event thiếu tenantId bị bỏ qua (TenantAwareEventHandler); message hỏng chỉ log.
 */
@Component
public class ReservationReadyConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReservationReadyConsumer.class);

    private final ObjectMapper mapper;
    private final EnqueueNotificationUseCase enqueue;

    public ReservationReadyConsumer(ObjectMapper mapper, EnqueueNotificationUseCase enqueue) {
        this.mapper = mapper;
        this.enqueue = enqueue;
    }

    @KafkaListener(topics = "${app.kafka.topics.reservation-events}",
            groupId = "${spring.kafka.consumer.group-id:support-service}-notification")
    public void onEvent(String message) {
        JsonNode root;
        NotificationTrigger trigger;
        try {
            root = mapper.readTree(message);
            trigger = NotificationEventMapper.fromReservation(root).orElse(null);
        } catch (Exception e) {
            log.warn("Bỏ qua message reservation.events không hợp lệ: {}", e.toString());
            return;
        }
        if (trigger == null) {
            return;
        }
        TenantAwareEventHandler.handle(root, () -> {
            int inserted = enqueue.execute(trigger);
            log.debug("Thông báo đặt trước sẵn sàng (eventId={}): {} delivery mới", trigger.eventId(), inserted);
        });
    }
}

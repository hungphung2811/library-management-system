package lms.support.notification.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.function.Function;
import lms.messaging.TenantAwareEventHandler;
import lms.support.notification.application.EnqueueNotificationUseCase;
import lms.support.notification.application.NotificationTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumer của các sự kiện sinh thông báo ra ngoài (ADR-0030 D10): đơn hàng/hoàn tiền/hóa đơn (sales.events), nhắc hạn
 * trả (borrowing.events, gói D), phạt mới (fine.events) và người nhận trực tiếp (notification.direct). Mỗi sự kiện được
 * ánh xạ rồi xếp hàng qua EnqueueNotificationUseCase (idempotent: giao lại ba lần vẫn một delivery). Loại sự kiện không
 * thuộc danh sách bị bỏ qua. Message hỏng hoặc thiếu tenantId chỉ log WARN, không kèm payload.
 *
 * <p>Các topic mang dữ liệu cá nhân (notification.direct có email) KHÔNG nằm trong danh sách topic của
 * AuditEventConsumer nên audit không lưu chúng vào Mongo.
 */
@Component
public class EventNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventNotificationConsumer.class);

    private final ObjectMapper mapper;
    private final EnqueueNotificationUseCase enqueue;

    public EventNotificationConsumer(ObjectMapper mapper, EnqueueNotificationUseCase enqueue) {
        this.mapper = mapper;
        this.enqueue = enqueue;
    }

    @KafkaListener(topics = "${app.kafka.topics.sales-events}",
            groupId = "${spring.kafka.consumer.group-id:support-service}-notification")
    public void onSales(String message) {
        handle("sales.events", message, NotificationEventMapper::fromSales);
    }

    @KafkaListener(topics = "${app.kafka.topics.borrowing-events}",
            groupId = "${spring.kafka.consumer.group-id:support-service}-notification")
    public void onBorrowing(String message) {
        handle("borrowing.events", message, NotificationEventMapper::fromBorrowing);
    }

    @KafkaListener(topics = "${app.kafka.topics.fine-events}",
            groupId = "${spring.kafka.consumer.group-id:support-service}-notification")
    public void onFine(String message) {
        handle("fine.events", message, NotificationEventMapper::fromFine);
    }

    @KafkaListener(topics = "${app.kafka.topics.notification-direct}",
            groupId = "${spring.kafka.consumer.group-id:support-service}-notification")
    public void onDirect(String message) {
        handle("notification.direct", message, NotificationEventMapper::fromDirect);
    }

    void handle(String topic, String message, Function<JsonNode, Optional<NotificationTrigger>> mapperFn) {
        JsonNode root;
        NotificationTrigger trigger;
        try {
            root = mapper.readTree(message);
            trigger = mapperFn.apply(root).orElse(null);
        } catch (Exception e) {
            // Không log payload (có thể chứa dữ liệu cá nhân): chỉ lớp lỗi
            log.warn("Bỏ qua message {} không hợp lệ: {}", topic, e.getClass().getSimpleName());
            return;
        }
        if (trigger == null) {
            return;
        }
        TenantAwareEventHandler.handle(tenantOf(root), trigger.eventType(), trigger.eventId(), () -> {
            int inserted = enqueue.execute(trigger);
            log.debug("Thông báo {} từ {} (eventId={}): {} delivery mới", trigger.type(), topic, trigger.eventId(),
                    inserted);
        });
    }

    private static java.util.UUID tenantOf(JsonNode root) {
        // notification.direct có thể phẳng (tenantId ở gốc) hoặc trong envelope: cả hai đều đọc từ gốc
        return NotificationEventMapper.uuid(root.path("tenantId"));
    }

}

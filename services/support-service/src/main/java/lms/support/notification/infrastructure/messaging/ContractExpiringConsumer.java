package lms.support.notification.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lms.messaging.TenantAwareEventHandler;
import lms.support.notification.application.EnqueueNotificationUseCase;
import lms.support.notification.application.NotificationTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Nghe identity-events: {@code TenantContractExpiringSoon} (ADR-0012 mục 2.4.3) thì tạo MỘT thông báo
 * {@code CONTRACT_EXPIRING} cho từng người nhận trong tenant của event và xếp email (ADR-0030, nhóm ACCOUNT bắt buộc).
 * Một event mang danh sách người nhận nên {@code eventId} thông báo trong ứng dụng của từng dòng là dẫn xuất tất định
 * {@code nameUUIDFromBytes(eventId + ":" + userId)}; delivery dùng eventId gốc + userId làm khóa khử trùng. Kafka giao
 * lại không nhân bản. Nội dung dựng từ payload (không có tiền, không có ghi chú nội bộ). Event thiếu tenantId bị bỏ
 * qua; message hỏng chỉ log.
 */
@Component
public class ContractExpiringConsumer {

    private static final Logger log = LoggerFactory.getLogger(ContractExpiringConsumer.class);

    private final ObjectMapper mapper;
    private final EnqueueNotificationUseCase enqueue;

    public ContractExpiringConsumer(ObjectMapper mapper, EnqueueNotificationUseCase enqueue) {
        this.mapper = mapper;
        this.enqueue = enqueue;
    }

    @KafkaListener(topics = "${app.kafka.topics.identity-events}",
            groupId = "${spring.kafka.consumer.group-id:support-service}-notification")
    public void onEvent(String message) {
        JsonNode root;
        List<NotificationTrigger> triggers;
        try {
            root = mapper.readTree(message);
            triggers = NotificationEventMapper.fromContractExpiring(root);
        } catch (Exception e) {
            log.warn("Bỏ qua message identity-events không hợp lệ: {}", e.toString());
            return;
        }
        if (triggers.isEmpty()) {
            return;
        }
        TenantAwareEventHandler.handle(root, () -> {
            for (NotificationTrigger t : triggers) {
                int inserted = enqueue.execute(t);
                log.debug("Thông báo hợp đồng sắp hết hạn (eventId={}): {} delivery mới", t.eventId(), inserted);
            }
        });
    }
}

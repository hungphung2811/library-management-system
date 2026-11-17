package lms.support.notification.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lms.messaging.TenantAwareEventHandler;
import lms.support.notification.application.CreateNotificationCommand;
import lms.support.notification.application.CreateNotificationUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Nghe identity-events: {@code TenantContractExpiringSoon} (ADR-0012 mục 2.4.3) thì tạo MỘT thông báo
 * {@code CONTRACT_EXPIRING} cho từng người nhận trong tenant của event. Một event mang danh sách người nhận nên
 * {@code eventId} của từng dòng là dẫn xuất tất định {@code nameUUIDFromBytes(eventId + ":" + userId)} (UNIQUE
 * (tenant_id, event_id) chỉ cho một dòng mỗi eventId): Kafka giao lại không nhân bản. Nội dung dựng từ payload
 * (không có tiền, không có ghi chú nội bộ). Event thiếu tenantId bị bỏ qua; message hỏng chỉ log.
 */
@Component
public class ContractExpiringConsumer {

    static final String EVENT_TYPE = "TenantContractExpiringSoon";
    static final String NOTIFICATION_TYPE = "CONTRACT_EXPIRING";
    private static final DateTimeFormatter VN_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final Logger log = LoggerFactory.getLogger(ContractExpiringConsumer.class);

    private final ObjectMapper mapper;
    private final CreateNotificationUseCase create;

    public ContractExpiringConsumer(ObjectMapper mapper, CreateNotificationUseCase create) {
        this.mapper = mapper;
        this.create = create;
    }

    @KafkaListener(topics = "${app.kafka.topics.identity-events}",
            groupId = "${spring.kafka.consumer.group-id:support-service}-notification")
    public void onEvent(String message) {
        JsonNode root;
        UUID eventId;
        String text;
        List<UUID> recipients = new ArrayList<>();
        try {
            root = mapper.readTree(message);
            if (!EVENT_TYPE.equals(root.path("eventType").asText())) {
                return;
            }
            eventId = UUID.fromString(root.path("eventId").asText());
            JsonNode payload = root.path("payload");
            String name = payload.path("contractName").asText("");
            LocalDate endDate = LocalDate.parse(payload.path("endDate").asText());
            int days = payload.path("daysBefore").asInt();
            for (JsonNode user : payload.path("recipientUserIds")) {
                recipients.add(UUID.fromString(user.asText()));
            }
            text = "Hợp đồng thuê dịch vụ" + (name.isBlank() ? "" : " \"" + name + "\"") + " sẽ hết hạn vào "
                    + VN_DATE.format(endDate) + " (còn khoảng " + days + " ngày). Vui lòng liên hệ để gia hạn.";
        } catch (Exception e) {
            log.warn("Bỏ qua message identity-events không hợp lệ: {}", e.toString());
            return;
        }
        TenantAwareEventHandler.handle(root, () -> {
            for (UUID userId : recipients) {
                UUID perUser = UUID.nameUUIDFromBytes((eventId + ":" + userId).getBytes(StandardCharsets.UTF_8));
                boolean created = create.execute(new CreateNotificationCommand(perUser, userId, NOTIFICATION_TYPE, text));
                log.debug("Thông báo hợp đồng sắp hết hạn (eventId={}, user={}): {}", eventId, userId,
                        created ? "đã tạo" : "bỏ qua vì đã có");
            }
        });
    }
}

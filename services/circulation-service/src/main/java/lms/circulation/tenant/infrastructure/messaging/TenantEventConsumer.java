package lms.circulation.tenant.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;
import lms.circulation.tenant.application.ChangeTenantStatusUseCase;
import lms.circulation.tenant.application.RegisterTenantUseCase;
import lms.circulation.tenant.domain.KnownTenantStatus;
import lms.messaging.TenantAwareEventHandler;
import lms.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Nghe event vòng đời tenant của identity-service (topic identity-events, Task 6):
 * <ul>
 *   <li>{@code TenantCreated} (payload {@code TenantCreatedEvent(UUID tenantId)}): ghi danh bạ + tạo quy tắc mượn
 *   mặc định cho tenant mới;</li>
 *   <li>{@code TenantStatusChanged} (payload {@code tenantId}, {@code status} ACTIVE|SUSPENDED): cập nhật danh bạ.</li>
 * </ul>
 * Xử lý trong TenantContext của envelope ({@link TenantAwareEventHandler}); envelope thiếu tenantId thì bỏ qua và log.
 * Payload mang tenantId khác envelope thì bỏ qua (không ghi dữ liệu vào tenant khác). Message hỏng chỉ log; lỗi tạm thời
 * (DB) được ném ra để Kafka giao lại, use case idempotent.
 */
@Component
public class TenantEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TenantEventConsumer.class);

    static final Set<String> TENANT_CREATED = Set.of("TenantCreated", "TenantCreatedEvent");
    static final Set<String> TENANT_STATUS_CHANGED = Set.of("TenantStatusChanged", "TenantStatusChangedEvent");

    private final ObjectMapper mapper;
    private final RegisterTenantUseCase registerTenant;
    private final ChangeTenantStatusUseCase changeStatus;

    public TenantEventConsumer(ObjectMapper mapper, RegisterTenantUseCase registerTenant,
                               ChangeTenantStatusUseCase changeStatus) {
        this.mapper = mapper;
        this.registerTenant = registerTenant;
        this.changeStatus = changeStatus;
    }

    @KafkaListener(topics = "${app.kafka.topics.identity-events}",
            groupId = "${spring.kafka.consumer.group-id:circulation-service}")
    public void onEvent(String message) {
        JsonNode root;
        String eventType;
        UUID payloadTenant;
        KnownTenantStatus status = null;
        try {
            root = mapper.readTree(message);
            eventType = root.path("eventType").asText("");
            boolean created = TENANT_CREATED.contains(eventType);
            boolean statusChanged = TENANT_STATUS_CHANGED.contains(eventType);
            if (!created && !statusChanged) return;
            JsonNode payload = root.path("payload");
            String tenantText = payload.path("tenantId").asText(null);
            payloadTenant = tenantText == null || tenantText.isBlank() ? null : UUID.fromString(tenantText);
            if (statusChanged) status = KnownTenantStatus.valueOf(payload.path("status").asText(""));
        } catch (Exception e) {
            log.warn("Bỏ qua message identity-events không hợp lệ: {}", e.toString());
            return;
        }
        final KnownTenantStatus newStatus = status;
        TenantAwareEventHandler.handle(root, () -> {
            UUID tenantId = TenantContext.require();
            if (payloadTenant != null && !payloadTenant.equals(tenantId)) {
                log.warn("Bỏ qua {}: tenantId của payload khác envelope (envelope={})", eventType, tenantId);
                return;
            }
            if (newStatus == null) {
                int rules = registerTenant.execute(tenantId);
                log.info("Tenant {} đã đăng ký ở circulation, tạo {} quy tắc mượn mặc định", tenantId, rules);
            } else {
                changeStatus.execute(tenantId, newStatus);
                log.info("Tenant {} chuyển trạng thái {}", tenantId, newStatus);
            }
        });
    }
}

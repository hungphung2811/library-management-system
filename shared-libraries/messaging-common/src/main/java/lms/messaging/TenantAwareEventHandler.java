package lms.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bọc xử lý event Kafka trong TenantContext của event. Event thiếu tenantId bị bỏ qua (log WARN, không log payload)
 * và use case không được gọi. Trả true nếu đã gọi xử lý.
 */
public final class TenantAwareEventHandler {

    private static final Logger log = LoggerFactory.getLogger(TenantAwareEventHandler.class);

    private TenantAwareEventHandler() {
    }

    public static boolean handle(EventEnvelope envelope, Runnable action) {
        return handle(envelope.tenantId(), envelope.eventType(), envelope.eventId(), action);
    }

    /** Dạng dùng cho consumer chỉ đọc JsonNode của envelope. */
    public static boolean handle(JsonNode envelope, Runnable action) {
        UUID tenantId = parseUuid(envelope.path("tenantId").asText(null));
        return handle(tenantId, envelope.path("eventType").asText(null), parseUuid(envelope.path("eventId").asText(null)),
                action);
    }

    public static boolean handle(UUID tenantId, String eventType, UUID eventId, Runnable action) {
        if (tenantId == null) {
            log.warn("Bỏ qua event thiếu tenantId: eventType={}, eventId={}", eventType, eventId);
            return false;
        }
        TenantContext.runAs(tenantId, action);
        return true;
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

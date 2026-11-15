package lms.identity.tenant.domain;

import java.time.Instant;
import java.util.UUID;
import lms.common.kernel.DomainEvent;

/**
 * Tenant mới được tạo (topic identity-events, eventType {@code TenantCreated}). Envelope mang tenantId của chính tenant
 * này; circulation tạo quy tắc mượn mặc định khi nhận (TenantEventConsumer).
 */
public record TenantCreatedEvent(UUID eventId, UUID tenantId, String code, Instant occurredAt) implements DomainEvent {

    public static final String TYPE = "TenantCreated";

    public TenantCreatedEvent(UUID tenantId, String code) {
        this(UUID.randomUUID(), tenantId, code, Instant.now());
    }

    @Override
    public String aggregateId() {
        return tenantId.toString();
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}

package lms.identity.tenant.domain;

import java.time.Instant;
import java.util.UUID;
import lms.common.kernel.DomainEvent;

/** Tenant bị tạm ngưng hoặc kích hoạt lại (topic identity-events, eventType {@code TenantStatusChanged}, payload {tenantId, status}). */
public record TenantStatusChangedEvent(UUID eventId, UUID tenantId, TenantStatus status, Instant occurredAt)
        implements DomainEvent {

    public static final String TYPE = "TenantStatusChanged";

    public TenantStatusChangedEvent(UUID tenantId, TenantStatus status) {
        this(UUID.randomUUID(), tenantId, status, Instant.now());
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

package lms.identity.tenant.domain;

import java.time.Instant;
import java.util.UUID;
import lms.common.kernel.DomainEvent;

/** Event bật/tắt module (topic identity-events). Không có consumer bắt buộc (ADR-0009 mục 2.3). */
public final class TenantModuleEvents {

    private TenantModuleEvents() {
    }

    /** eventType {@code TenantModuleEnabled}, payload {tenantId, module, actorId}. */
    public record Enabled(UUID eventId, UUID tenantId, String module, UUID actorId, Instant occurredAt)
            implements DomainEvent {

        public static final String TYPE = "TenantModuleEnabled";

        public Enabled(UUID tenantId, ModuleCode module, UUID actorId) {
            this(UUID.randomUUID(), tenantId, module.name(), actorId, Instant.now());
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

    /** eventType {@code TenantModuleDisabled}, payload {tenantId, module, actorId}. */
    public record Disabled(UUID eventId, UUID tenantId, String module, UUID actorId, Instant occurredAt)
            implements DomainEvent {

        public static final String TYPE = "TenantModuleDisabled";

        public Disabled(UUID tenantId, ModuleCode module, UUID actorId) {
            this(UUID.randomUUID(), tenantId, module.name(), actorId, Instant.now());
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
}

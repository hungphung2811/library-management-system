package lms.identity.tenant.domain;

import java.time.Instant;
import java.util.UUID;
import lms.common.kernel.DomainEvent;

/**
 * Bản ghi audit của một thao tác nền tảng (ai, làm gì, tenant nào, khi nào). tenantId null với thao tác trên tổ chức;
 * targetId là đối tượng bị tác động (tenant, tổ chức hoặc tài khoản).
 */
public record PlatformOperationEvent(UUID eventId, String operation, UUID actorId, UUID tenantId, UUID targetId,
                                     String detail, Instant occurredAt) implements DomainEvent {

    public static final String TYPE = "PlatformOperationPerformed";

    public static PlatformOperationEvent of(String operation, UUID actorId, UUID tenantId, UUID targetId,
                                            String detail) {
        return new PlatformOperationEvent(UUID.randomUUID(), operation, actorId, tenantId, targetId, detail,
                Instant.now());
    }

    @Override
    public String aggregateId() {
        return targetId != null ? targetId.toString() : String.valueOf(tenantId);
    }

    @Override
    public String eventType() {
        return TYPE;
    }
}

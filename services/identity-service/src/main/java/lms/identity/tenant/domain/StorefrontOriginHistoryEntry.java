package lms.identity.tenant.domain;

import java.time.Instant;
import java.util.UUID;

/** Một dòng lịch sử chỉ thêm (tenant_storefront_origin_history). */
public record StorefrontOriginHistoryEntry(UUID id, UUID tenantId, UUID originId, String origin,
                                           StorefrontOriginAction action, UUID actorId, Instant occurredAt) {

    public static StorefrontOriginHistoryEntry of(StorefrontOriginEntry e, StorefrontOriginAction action, UUID actor,
                                                  Instant at) {
        return new StorefrontOriginHistoryEntry(UUID.randomUUID(), e.tenantId(), e.id(), e.origin(), action, actor, at);
    }
}

package lms.identity.tenant.domain;

import java.time.Instant;
import java.util.UUID;

/** Một origin đã khai báo của tenant (bảng tenant_storefront_origins). */
public record StorefrontOriginEntry(UUID id, UUID tenantId, String origin, boolean enabled, Instant createdAt,
                                    UUID createdBy, Instant updatedAt, UUID updatedBy) {

    public StorefrontOriginEntry withEnabled(boolean value, Instant at, UUID actor) {
        return new StorefrontOriginEntry(id, tenantId, origin, value, createdAt, createdBy, at, actor);
    }
}

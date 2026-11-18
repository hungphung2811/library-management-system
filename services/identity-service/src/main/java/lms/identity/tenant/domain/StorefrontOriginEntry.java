package lms.identity.tenant.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Một origin đã khai báo của tenant (bảng tenant_storefront_origins). {@code applicationId} null = áp cho mọi
 * Application kiểu website của tenant (dữ liệu cũ, tương thích ngược); khác null = domain allowlist của đúng Application đó.
 */
public record StorefrontOriginEntry(UUID id, UUID tenantId, String origin, boolean enabled, Instant createdAt,
                                    UUID createdBy, Instant updatedAt, UUID updatedBy, UUID applicationId) {

    /** Origin cấp tenant (không gắn Application). */
    public StorefrontOriginEntry(UUID id, UUID tenantId, String origin, boolean enabled, Instant createdAt,
                                 UUID createdBy, Instant updatedAt, UUID updatedBy) {
        this(id, tenantId, origin, enabled, createdAt, createdBy, updatedAt, updatedBy, null);
    }

    public StorefrontOriginEntry withEnabled(boolean value, Instant at, UUID actor) {
        return new StorefrontOriginEntry(id, tenantId, origin, value, createdAt, createdBy, at, actor, applicationId);
    }
}

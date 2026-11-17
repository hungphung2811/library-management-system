package lms.identity.tenant.application;

import java.time.Instant;
import java.util.UUID;
import lms.identity.tenant.domain.StorefrontOriginEntry;

/** Origin đầy đủ cho platform admin (có người/ngày tạo, cập nhật). KHÔNG dùng cho tenant admin. */
public record StorefrontOriginResponse(UUID id, String origin, boolean enabled, Instant createdAt, UUID createdBy,
                                       Instant updatedAt, UUID updatedBy) {

    public static StorefrontOriginResponse from(StorefrontOriginEntry e) {
        return new StorefrontOriginResponse(e.id(), e.origin(), e.enabled(), e.createdAt(), e.createdBy(),
                e.updatedAt(), e.updatedBy());
    }
}

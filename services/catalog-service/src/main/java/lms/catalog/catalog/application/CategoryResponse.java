package lms.catalog.catalog.application;

import java.util.UUID;

/** Thể loại trả cho client (không có thông tin bổ sung). */
public record CategoryResponse(UUID id, String name, String description, UUID parentId, String parentName,
                               boolean active, UUID tenantId) {

    public static CategoryResponse from(ReferenceResponse r) {
        return new CategoryResponse(r.id(), r.name(), r.description(), r.parentId(), r.parentName(), r.active(),
                r.tenantId());
    }
}

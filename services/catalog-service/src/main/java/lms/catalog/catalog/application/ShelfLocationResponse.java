package lms.catalog.catalog.application;

import java.util.UUID;

/** Vị trí kệ trả cho client (code là mã vị trí). */
public record ShelfLocationResponse(UUID id, String code, String description, boolean active, UUID tenantId) {

    public static ShelfLocationResponse from(ReferenceResponse r) {
        return new ShelfLocationResponse(r.id(), r.name(), r.description(), r.active(), r.tenantId());
    }
}

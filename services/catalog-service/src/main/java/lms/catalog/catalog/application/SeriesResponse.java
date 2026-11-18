package lms.catalog.catalog.application;

import java.util.UUID;

/** Bộ sách trả cho client (không có thông tin bổ sung). */
public record SeriesResponse(UUID id, String name, String description, boolean active, UUID tenantId) {

    public static SeriesResponse from(ReferenceResponse r) {
        return new SeriesResponse(r.id(), r.name(), r.description(), r.active(), r.tenantId());
    }
}

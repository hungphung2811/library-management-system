package lms.catalog.catalog.application;

import java.util.UUID;

/** Vị trí kệ trả cho client (code là mã vị trí); floor, zone, capacity là thông tin bổ sung tùy chọn. */
public record ShelfLocationResponse(UUID id, String code, String description, boolean active, UUID tenantId,
                                    String floor, String zone, Integer capacity) {

    public ShelfLocationResponse(UUID id, String code, String description, boolean active, UUID tenantId) {
        this(id, code, description, active, tenantId, null, null, null);
    }

    public static ShelfLocationResponse from(ReferenceResponse r) {
        var x = r.extras();
        return new ShelfLocationResponse(r.id(), r.name(), r.description(), r.active(), r.tenantId(), x.floor(),
                x.zone(), x.capacity());
    }
}

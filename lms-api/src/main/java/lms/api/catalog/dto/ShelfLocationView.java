package lms.api.catalog.dto;

import java.util.UUID;

/** Vị trí kệ; floor, zone, capacity là thông tin bổ sung tùy chọn (null nếu chưa nhập). */
public record ShelfLocationView(UUID id, String code, String description, boolean active, UUID tenantId,
                                String floor, String zone, Integer capacity) {

    public ShelfLocationView(UUID id, String code, String description, boolean active, UUID tenantId) {
        this(id, code, description, active, tenantId, null, null, null);
    }
}

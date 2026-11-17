package lms.api.catalog.dto;

import java.util.UUID;

/** Bản sao; shelfLocationId/shelfLocationCode là vị trí kệ chuẩn tùy chọn (null nếu chưa gán). */
public record CopyView(UUID id, UUID titleId, String barcode, String status, UUID tenantId, UUID shelfLocationId,
                       String shelfLocationCode) {

    /** Dạng không kèm vị trí kệ (tương thích cũ). */
    public CopyView(UUID id, UUID titleId, String barcode, String status, UUID tenantId) {
        this(id, titleId, barcode, status, tenantId, null, null);
    }
}

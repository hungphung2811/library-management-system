package lms.api.catalog.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Bản sao; shelfLocationId/shelfLocationCode là vị trí kệ chuẩn tùy chọn (null nếu chưa gán). Sáu trường từ acquiredDate
 * trở đi là thông tin nhập kho tùy chọn (null nếu chưa nhập): acquisitionSource PURCHASE|DONATION|EXCHANGE|OTHER,
 * conditionGrade NEW|GOOD|FAIR|WORN.
 */
public record CopyView(UUID id, UUID titleId, String barcode, String status, UUID tenantId, UUID shelfLocationId,
                       String shelfLocationCode, LocalDate acquiredDate, String acquisitionSource, Long purchasePrice,
                       String conditionGrade, String conditionNote, LocalDate lastInventoryDate) {

    /** Dạng 7 trường (chưa có thông tin nhập kho). */
    public CopyView(UUID id, UUID titleId, String barcode, String status, UUID tenantId, UUID shelfLocationId,
                    String shelfLocationCode) {
        this(id, titleId, barcode, status, tenantId, shelfLocationId, shelfLocationCode, null, null, null, null, null,
                null);
    }

    /** Dạng không kèm vị trí kệ (tương thích cũ). */
    public CopyView(UUID id, UUID titleId, String barcode, String status, UUID tenantId) {
        this(id, titleId, barcode, status, tenantId, null, null);
    }
}

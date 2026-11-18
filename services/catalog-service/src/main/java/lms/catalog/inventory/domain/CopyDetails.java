package lms.catalog.inventory.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Thông tin bản sao kèm tên và giá bìa của đầu sách (circulation dùng để tính phí phạt, QĐ4) và tình trạng/giá mua
 * của chính bản sao (circulation chụp tình trạng lúc mượn và tính đền bù theo giá mua). conditionGrade là tên
 * {@code ConditionGrade} (NEW, GOOD, FAIR, WORN), null nếu chưa ghi nhận.
 */
public record CopyDetails(UUID id, UUID titleId, String titleName, String barcode, CopyStatus status,
                          BigDecimal coverPrice, UUID shelfLocationId, String shelfLocationCode,
                          Long purchasePrice, String conditionGrade, String conditionNote) {

    public CopyDetails(UUID id, UUID titleId, String titleName, String barcode, CopyStatus status,
                       BigDecimal coverPrice, UUID shelfLocationId, String shelfLocationCode) {
        this(id, titleId, titleName, barcode, status, coverPrice, shelfLocationId, shelfLocationCode, null, null, null);
    }

    public CopyDetails(UUID id, UUID titleId, String titleName, String barcode, CopyStatus status,
                       BigDecimal coverPrice) {
        this(id, titleId, titleName, barcode, status, coverPrice, null, null, null, null, null);
    }
}

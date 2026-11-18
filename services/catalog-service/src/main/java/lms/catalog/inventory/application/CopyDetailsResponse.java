package lms.catalog.inventory.application;

import lms.catalog.inventory.domain.CopyDetails;
import lms.catalog.inventory.domain.CopyStatus;
import java.math.BigDecimal;
import java.util.UUID;

/** purchasePrice, conditionGrade, conditionNote (additive) do circulation đọc để chụp tình trạng và tính đền bù. */
public record CopyDetailsResponse(UUID id, UUID titleId, String titleName, String barcode, CopyStatus status,
                                  BigDecimal coverPrice, UUID shelfLocationId, String shelfLocationCode,
                                  Long purchasePrice, String conditionGrade, String conditionNote, UUID workId) {

    /** Dạng 11 trường (chưa có workId, ADR-0026). */
    public CopyDetailsResponse(UUID id, UUID titleId, String titleName, String barcode, CopyStatus status,
                               BigDecimal coverPrice, UUID shelfLocationId, String shelfLocationCode,
                               Long purchasePrice, String conditionGrade, String conditionNote) {
        this(id, titleId, titleName, barcode, status, coverPrice, shelfLocationId, shelfLocationCode, purchasePrice,
                conditionGrade, conditionNote, null);
    }

    /** Bản có workId (tác phẩm của ấn bản chứa bản sao; null nếu ấn bản chưa gắn tác phẩm). */
    public CopyDetailsResponse withWorkId(UUID workId) {
        return new CopyDetailsResponse(id, titleId, titleName, barcode, status, coverPrice, shelfLocationId,
                shelfLocationCode, purchasePrice, conditionGrade, conditionNote, workId);
    }

    public CopyDetailsResponse(UUID id, UUID titleId, String titleName, String barcode, CopyStatus status,
                               BigDecimal coverPrice, UUID shelfLocationId, String shelfLocationCode) {
        this(id, titleId, titleName, barcode, status, coverPrice, shelfLocationId, shelfLocationCode, null, null, null);
    }

    public CopyDetailsResponse(UUID id, UUID titleId, String titleName, String barcode, CopyStatus status,
                               BigDecimal coverPrice) {
        this(id, titleId, titleName, barcode, status, coverPrice, null, null, null, null, null);
    }

    public static CopyDetailsResponse from(CopyDetails d) {
        return new CopyDetailsResponse(d.id(), d.titleId(), d.titleName(), d.barcode(), d.status(), d.coverPrice(),
                d.shelfLocationId(), d.shelfLocationCode(), d.purchasePrice(), d.conditionGrade(), d.conditionNote());
    }
}

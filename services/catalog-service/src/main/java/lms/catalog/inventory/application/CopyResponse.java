package lms.catalog.inventory.application;

import lms.catalog.inventory.domain.AcquisitionSource;
import lms.catalog.inventory.domain.BookCopy;
import lms.catalog.inventory.domain.ConditionGrade;
import lms.catalog.inventory.domain.CopyAcquisition;
import lms.catalog.inventory.domain.CopyStatus;
import java.time.LocalDate;
import java.util.UUID;

/** Bản sao trả cho client; sáu trường từ acquiredDate trở đi là thông tin nhập kho tùy chọn (null nếu chưa nhập). */
public record CopyResponse(UUID id, UUID titleId, String barcode, CopyStatus status, UUID tenantId,
                           UUID shelfLocationId, String shelfLocationCode, LocalDate acquiredDate,
                           AcquisitionSource acquisitionSource, Long purchasePrice, ConditionGrade conditionGrade,
                           String conditionNote, LocalDate lastInventoryDate) {

    /** Dạng 7 trường (chưa có thông tin nhập kho). */
    public CopyResponse(UUID id, UUID titleId, String barcode, CopyStatus status, UUID tenantId,
                        UUID shelfLocationId, String shelfLocationCode) {
        this(id, titleId, barcode, status, tenantId, shelfLocationId, shelfLocationCode, null, null, null, null, null,
                null);
    }

    /** Dạng không kèm vị trí kệ (tương thích cũ). */
    public CopyResponse(UUID id, UUID titleId, String barcode, CopyStatus status, UUID tenantId) {
        this(id, titleId, barcode, status, tenantId, null, null);
    }

    public static CopyResponse from(BookCopy copy) {
        return from(copy, null);
    }

    public static CopyResponse from(BookCopy copy, String shelfLocationCode) {
        CopyAcquisition a = copy.acquisition();
        return new CopyResponse(copy.id(), copy.titleId(), copy.barcode(), copy.status(), copy.tenantId(),
                copy.shelfLocationId(), shelfLocationCode, a.acquiredDate(), a.acquisitionSource(), a.purchasePrice(),
                a.conditionGrade(), a.conditionNote(), a.lastInventoryDate());
    }
}

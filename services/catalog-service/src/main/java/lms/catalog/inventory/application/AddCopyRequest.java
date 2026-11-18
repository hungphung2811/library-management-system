package lms.catalog.inventory.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;
import lms.catalog.inventory.domain.AcquisitionSource;
import lms.catalog.inventory.domain.ConditionGrade;
import lms.catalog.inventory.domain.CopyAcquisition;

/** shelfLocationId là vị trí kệ chuẩn tùy chọn; sáu trường từ acquiredDate trở đi là thông tin nhập kho, đều tùy chọn. */
public record AddCopyRequest(@NotNull UUID titleId, @NotBlank @Size(max = 64) String barcode,
                             UUID shelfLocationId,
        @PastOrPresent LocalDate acquiredDate,
        AcquisitionSource acquisitionSource,
        @Min(0) Long purchasePrice,
        ConditionGrade conditionGrade,
        @Size(max = 300) String conditionNote,
        @PastOrPresent LocalDate lastInventoryDate) {

    public AddCopyRequest(UUID titleId, String barcode, UUID shelfLocationId) {
        this(titleId, barcode, shelfLocationId, null, null, null, null, null, null);
    }

    public AddCopyRequest(UUID titleId, String barcode) {
        this(titleId, barcode, null);
    }

    public CopyAcquisition acquisition() {
        return new CopyAcquisition(acquiredDate, acquisitionSource, purchasePrice, conditionGrade, conditionNote,
                lastInventoryDate);
    }
}

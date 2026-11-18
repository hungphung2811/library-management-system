package lms.catalog.inventory.application;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lms.catalog.inventory.domain.AcquisitionSource;
import lms.catalog.inventory.domain.ConditionGrade;
import lms.catalog.inventory.domain.CopyAcquisition;

/** Thay toàn bộ thông tin nhập kho và tình trạng của bản sao: thiếu trường nghĩa là xóa giá trị đó. */
public record UpdateCopyAcquisitionRequest(
        @PastOrPresent LocalDate acquiredDate,
        AcquisitionSource acquisitionSource,
        @Min(0) Long purchasePrice,
        ConditionGrade conditionGrade,
        @Size(max = 300) String conditionNote,
        @PastOrPresent LocalDate lastInventoryDate) {

    public CopyAcquisition acquisition() {
        return new CopyAcquisition(acquiredDate, acquisitionSource, purchasePrice, conditionGrade, conditionNote,
                lastInventoryDate);
    }
}

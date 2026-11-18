package lms.api.catalog.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Thay TOÀN BỘ thông tin nhập kho và tình trạng của bản sao: thiếu trường nghĩa là xóa giá trị đó. */
public record UpdateCopyAcquisitionRequest(
        @PastOrPresent LocalDate acquiredDate,
        @Pattern(regexp = "PURCHASE|DONATION|EXCHANGE|OTHER", message = "phải là PURCHASE, DONATION, EXCHANGE hoặc OTHER") String acquisitionSource,
        @Min(0) Long purchasePrice,
        @Pattern(regexp = "NEW|GOOD|FAIR|WORN", message = "phải là NEW, GOOD, FAIR hoặc WORN") String conditionGrade,
        @Size(max = 300) String conditionNote,
        @PastOrPresent LocalDate lastInventoryDate) {
}

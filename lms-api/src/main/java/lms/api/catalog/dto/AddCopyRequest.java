package lms.api.catalog.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * shelfLocationId là vị trí kệ chuẩn tùy chọn; sáu trường từ acquiredDate trở đi là thông tin nhập kho, đều tùy chọn
 * (purchasePrice chỉ hợp lệ khi acquisitionSource = PURCHASE: catalog kiểm, trả 422 INVALID_COPY).
 */
public record AddCopyRequest(@NotNull UUID titleId, @NotBlank @Size(max = 64) String barcode, UUID shelfLocationId,
        @PastOrPresent LocalDate acquiredDate,
        @Pattern(regexp = "PURCHASE|DONATION|EXCHANGE|OTHER", message = "phải là PURCHASE, DONATION, EXCHANGE hoặc OTHER") String acquisitionSource,
        @Min(0) Long purchasePrice,
        @Pattern(regexp = "NEW|GOOD|FAIR|WORN", message = "phải là NEW, GOOD, FAIR hoặc WORN") String conditionGrade,
        @Size(max = 300) String conditionNote,
        @PastOrPresent LocalDate lastInventoryDate) {

    public AddCopyRequest(UUID titleId, String barcode, UUID shelfLocationId) {
        this(titleId, barcode, shelfLocationId, null, null, null, null, null, null);
    }

    public AddCopyRequest(UUID titleId, String barcode) {
        this(titleId, barcode, null);
    }
}

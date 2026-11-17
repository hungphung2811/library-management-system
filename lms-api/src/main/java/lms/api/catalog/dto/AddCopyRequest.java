package lms.api.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** shelfLocationId là vị trí kệ chuẩn tùy chọn. */
public record AddCopyRequest(@NotNull UUID titleId, @NotBlank @Size(max = 64) String barcode, UUID shelfLocationId) {

    public AddCopyRequest(UUID titleId, String barcode) {
        this(titleId, barcode, null);
    }
}

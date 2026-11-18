package lms.api.circulation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Trả một bản sao của phiếu theo id. condition mặc định GOOD; các trường hỏng như {@link ReturnCopyRequest}. */
public record ReturnBookRequest(@NotNull UUID bookCopyId, @Pattern(regexp = "GOOD|DAMAGED") String condition,
                                @Size(max = 30) String damageLevelCode, @Size(max = 1000) String damageNote,
                                @Size(max = 3) List<@Size(max = 500) String> evidenceUrls) {

    public ReturnBookRequest(UUID bookCopyId) {
        this(bookCopyId, null, null, null, null);
    }
}

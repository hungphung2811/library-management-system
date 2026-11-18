package lms.api.circulation.dto;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Báo mất bản sao đang mượn, chỉ định bằng bookCopyId hoặc barcode; damageNote và evidenceUrls là bằng chứng tùy chọn. */
public record ReportLostRequest(UUID bookCopyId, String barcode, @Size(max = 1000) String damageNote,
                                @Size(max = 3) List<@Size(max = 500) String> evidenceUrls) {

    public ReportLostRequest(UUID bookCopyId, String barcode) {
        this(bookCopyId, barcode, null, null);
    }
}

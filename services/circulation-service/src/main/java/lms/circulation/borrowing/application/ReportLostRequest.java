package lms.circulation.borrowing.application;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Chỉ định bản sao bị mất bằng bookCopyId hoặc barcode. damageNote (tối đa 1000 ký tự) và evidenceUrls (tối đa 3 URL
 * http/https, mỗi URL tối đa 500 ký tự, chỉ lưu không tải) là bằng chứng gắn với khoản bồi thường.
 */
public record ReportLostRequest(UUID bookCopyId, String barcode, @Size(max = 1000) String damageNote,
                                @Size(max = 3) List<@Size(max = 500) String> evidenceUrls) {

    public ReportLostRequest(UUID bookCopyId, String barcode) {
        this(bookCopyId, barcode, null, null);
    }
}

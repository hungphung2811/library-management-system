package lms.api.circulation.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Trả theo bookCopyId hoặc barcode (máy quét). condition: GOOD (mặc định) hoặc DAMAGED. Khi DAMAGED: damageLevelCode
 * (bắt buộc nếu đơn vị đã cấu hình bậc hỏng), damageNote (tối đa 1000), evidenceUrls (tối đa 3 URL http/https, mỗi URL tối
 * đa 500 ký tự, chỉ lưu).
 */
public record ReturnCopyRequest(UUID bookCopyId, String barcode,
                                @Pattern(regexp = "GOOD|DAMAGED") String condition,
                                @Size(max = 30) String damageLevelCode, @Size(max = 1000) String damageNote,
                                @Size(max = 3) List<@Size(max = 500) String> evidenceUrls) {

    public ReturnCopyRequest(UUID bookCopyId, String barcode, String condition) {
        this(bookCopyId, barcode, condition, null, null, null);
    }
}

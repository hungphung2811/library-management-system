package lms.circulation.borrowing.application;

import jakarta.validation.constraints.Size;
import lms.circulation.borrowing.domain.ItemCondition;
import java.util.List;
import java.util.UUID;

/**
 * Chỉ định bản sao bằng bookCopyId hoặc barcode (máy quét); condition mặc định GOOD, chỉ nhận GOOD hoặc DAMAGED.
 * Khi DAMAGED: damageLevelCode chọn bậc hỏng của tenant (bắt buộc nếu tenant đã cấu hình bậc hoạt động; request cũ không
 * kèm bậc thì tính tỉ lệ hỏng mặc định, mã LEGACY_DAMAGED), damageNote tối đa 1000 ký tự, evidenceUrls tối đa 3 URL
 * http/https mỗi URL tối đa 500 ký tự (chỉ lưu, không tải). Các trường hỏng bị từ chối khi condition là GOOD.
 */
public record ReturnBookRequest(UUID bookCopyId, String barcode, ItemCondition condition,
                                @Size(max = 30) String damageLevelCode, @Size(max = 1000) String damageNote,
                                @Size(max = 3) List<@Size(max = 500) String> evidenceUrls) {

    public ReturnBookRequest(UUID bookCopyId, String barcode, ItemCondition condition) {
        this(bookCopyId, barcode, condition, null, null, null);
    }
}

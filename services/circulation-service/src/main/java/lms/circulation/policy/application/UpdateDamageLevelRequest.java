package lms.circulation.policy.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Sửa nhãn, tỉ lệ, thứ tự, bật/tắt. code (nếu gửi) phải trùng mã trên đường dẫn: mã không đổi được (422
 * DAMAGE_LEVEL_CODE_IMMUTABLE). version: giá trị đã đọc từ GET; gửi thì kiểm khóa lạc quan (409 nếu đã bị sửa).
 */
public record UpdateDamageLevelRequest(String code, @NotBlank String label, @NotNull BigDecimal rate,
                                       @NotNull Integer sortOrder, @NotNull Boolean active, Long version) {
}

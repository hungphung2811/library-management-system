package lms.circulation.policy.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Bậc hỏng do tenant định nghĩa. code là khóa ổn định (chữ in hoa, số, gạch dưới, 2 đến 30 ký tự, không đổi sau khi
 * tạo); label là nhãn hiển thị (sửa được); rate là tỉ lệ trên giá gốc, 0 đến 5.00; active=false thì không chọn được cho
 * khoản mới nhưng vẫn hiển thị ở khoản cũ.
 */
public record DamageLevel(String code, String label, BigDecimal rate, int sortOrder, boolean active) {

    public static final Pattern CODE = Pattern.compile("^[A-Z0-9_]{2,30}$");
    public static final BigDecimal MAX_RATE = new BigDecimal("5");
    /** Giới hạn số bậc mỗi tenant (đã duyệt đề xuất 10). */
    public static final int MAX_LEVELS_PER_TENANT = 10;

    /** Mã dành cho DAMAGED cũ không kèm bậc (tính theo tỉ lệ hỏng cấu hình chung, mặc định 50%). */
    public static final String LEGACY_CODE = "LEGACY_DAMAGED";
    public static final String LEGACY_LABEL = "Hỏng (mặc định cũ)";

    public DamageLevel {
        if (code == null || !CODE.matcher(code).matches()) {
            throw CompensationRuleException.invalid("INVALID_DAMAGE_LEVEL",
                    "Mã bậc hỏng gồm chữ in hoa, số, gạch dưới, 2 đến 30 ký tự");
        }
        label = label == null ? "" : label.strip();
        if (label.isEmpty() || label.length() > 100) {
            throw CompensationRuleException.invalid("INVALID_DAMAGE_LEVEL", "Nhãn bậc hỏng bắt buộc, tối đa 100 ký tự");
        }
        if (rate == null || rate.signum() < 0 || rate.compareTo(MAX_RATE) > 0) {
            throw CompensationRuleException.invalid("INVALID_DAMAGE_LEVEL", "Tỉ lệ bậc hỏng phải từ 0 đến 5.00");
        }
        rate = rate.setScale(4, java.math.RoundingMode.HALF_UP);
        if (sortOrder < 0 || sortOrder > 1000) {
            throw CompensationRuleException.invalid("INVALID_DAMAGE_LEVEL", "Thứ tự hiển thị phải từ 0 đến 1000");
        }
    }

    /** Hạt giống khi tenant chưa cấu hình bậc nào: chỉ là mặc định trong code, không ghi vào DB. */
    public static List<DamageLevel> seeds() {
        return List.of(
                new DamageLevel("MINOR", "Hỏng nhẹ", new BigDecimal("0.20"), 1, true),
                new DamageLevel("MAJOR", "Hỏng nặng", new BigDecimal("0.50"), 2, true));
    }
}

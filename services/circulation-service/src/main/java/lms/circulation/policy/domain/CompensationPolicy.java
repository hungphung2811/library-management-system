package lms.circulation.policy.domain;

import java.math.BigDecimal;

/**
 * Chính sách đền bù hỏng/mất của một đơn vị (tenant). Value object thuần, tự kiểm hợp lệ. Mặc định
 * ({@link #defaults()}) trùng hành vi trước khi có cấu hình: mất 150% giá bìa, không sàn/trần, hạn tìm lại 30 ngày, phí
 * xử lý hoàn 0. Chỉ áp cho khoản phạt lập sau khi lưu; khoản cũ không tính lại. Sàn/trần áp cho từng khoản hỏng và
 * mất, không áp cho phí trễ. Tiền là VND nguyên (làm tròn HALF_UP về số nguyên).
 *
 * @param lostRate            tỉ lệ bồi thường mất trên giá gốc, 0 đến 5
 * @param minAmount           sàn mỗi khoản (null = không có)
 * @param maxAmount           trần mỗi khoản (null = không có)
 * @param foundWindowDays     số ngày sau báo mất còn được tìm lại và hoàn khoản
 * @param refundProcessingFee phí xử lý trừ vào tiền hoàn
 */
public record CompensationPolicy(BigDecimal lostRate, PriceSourceKind priceSource, BigDecimal minAmount,
                                 BigDecimal maxAmount, int foundWindowDays, BigDecimal refundProcessingFee) {

    public static final BigDecimal DEFAULT_LOST_RATE = new BigDecimal("1.5");
    public static final int DEFAULT_FOUND_WINDOW_DAYS = 30;
    public static final BigDecimal MAX_RATE = new BigDecimal("5");
    public static final BigDecimal MAX_MONEY = new BigDecimal("1000000000");

    public CompensationPolicy {
        if (lostRate == null || lostRate.signum() < 0 || lostRate.compareTo(MAX_RATE) > 0) {
            throw CompensationRuleException.invalid("INVALID_COMPENSATION_POLICY", "Tỉ lệ mất phải từ 0 đến 5.00");
        }
        if (priceSource == null) {
            throw CompensationRuleException.invalid("INVALID_COMPENSATION_POLICY", "Cần chọn nguồn giá");
        }
        minAmount = money("Sàn", minAmount);
        maxAmount = money("Trần", maxAmount);
        if (minAmount != null && maxAmount != null && minAmount.compareTo(maxAmount) > 0) {
            throw CompensationRuleException.invalid("COMPENSATION_BOUNDS_INVALID", "Sàn không được lớn hơn trần");
        }
        if (foundWindowDays < 1 || foundWindowDays > 365) {
            throw CompensationRuleException.invalid("INVALID_COMPENSATION_POLICY", "Hạn tìm lại phải từ 1 đến 365 ngày");
        }
        refundProcessingFee = refundProcessingFee == null ? BigDecimal.ZERO : money("Phí xử lý hoàn", refundProcessingFee);
    }

    private static BigDecimal money(String label, BigDecimal value) {
        if (value == null) return null;
        if (value.signum() < 0 || value.compareTo(MAX_MONEY) > 0) {
            throw CompensationRuleException.invalid("INVALID_COMPENSATION_POLICY", label + " phải từ 0 đến 1.000.000.000");
        }
        return value.setScale(0, java.math.RoundingMode.HALF_UP);
    }

    public static CompensationPolicy defaults() {
        return new CompensationPolicy(DEFAULT_LOST_RATE, PriceSourceKind.COVER_PRICE, null, null,
                DEFAULT_FOUND_WINDOW_DAYS, BigDecimal.ZERO);
    }
}

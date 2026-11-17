package lms.identity.contract.domain;

import java.util.Locale;

/** Chu kỳ thanh toán tham khảo (chưa có hóa đơn tự động): đơn vị + số lượng &gt; 0. */
public record BillingCycle(Unit unit, int value) {

    public enum Unit { DAY, MONTH, YEAR }

    public BillingCycle {
        if (unit == null || value <= 0) {
            throw new ContractException(ContractException.Reason.INVALID_CONTRACT,
                    "Chu kỳ thanh toán phải có đơn vị (DAY/MONTH/YEAR) và số lượng lớn hơn 0");
        }
    }

    /** Cả hai null thì không có chu kỳ; chỉ một trong hai là không hợp lệ. */
    public static BillingCycle ofNullable(String unit, Integer value) {
        if (unit == null && value == null) return null;
        if (unit == null || value == null) {
            throw new ContractException(ContractException.Reason.INVALID_CONTRACT,
                    "Chu kỳ thanh toán phải có đủ đơn vị và số lượng");
        }
        try {
            return new BillingCycle(Unit.valueOf(unit.trim().toUpperCase(Locale.ROOT)), value);
        } catch (IllegalArgumentException e) {
            throw new ContractException(ContractException.Reason.INVALID_CONTRACT,
                    "Đơn vị chu kỳ không hợp lệ: " + unit);
        }
    }
}

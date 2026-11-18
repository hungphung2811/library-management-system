package lms.circulation.policy.domain;

import java.math.BigDecimal;

/**
 * Chính sách mượn trả của một đơn vị (tenant). Value object thuần, tự kiểm hợp lệ.
 * <p>
 * Giá trị mặc định ({@link #defaults()}) trùng hành vi trước khi có cấu hình: mượn 14 ngày, gia hạn tối đa 2 lần
 * mỗi lần 14 ngày, tối đa 5 cuốn đang mượn, phạt 5.000/cuốn/ngày, không có ngày ân hạn, giữ chỗ đặt trước 3 ngày.
 * <p>
 * Chỉ áp dụng cho phiếu/thao tác phát sinh SAU khi lưu: phiếu đã lập giữ nguyên hạn trả và số ngày mượn cũ,
 * khoản phạt đã lập và đặt trước đã giữ bản sao không bị tính lại.
 *
 * @param finePerDay tiền phạt trễ mỗi cuốn mỗi ngày, số nguyên theo đơn vị tiền nhỏ nhất (VND)
 * @param graceDays  số ngày trễ đầu tiên được miễn phạt
 * @param dueReminderDaysBefore        nhắc sắp đến hạn trước bao nhiêu ngày (ADR-0030 D11), 0 = tắt
 * @param overdueReminderIntervalDays  chu kỳ nhắc quá hạn, tính theo ngày giữa hai lần nhắc
 * @param overdueReminderMax           trần số lần nhắc quá hạn mỗi (phiếu, hạn trả), 0 = tắt
 */
public record CirculationPolicy(int loanDays, int maxRenewals, int renewalDays, int maxActiveLoansPerReader,
                                long finePerDay, int graceDays, int reservationHoldDays,
                                int dueReminderDaysBefore, int overdueReminderIntervalDays, int overdueReminderMax) {

    public static final int DEFAULT_LOAN_DAYS = 14;
    public static final int DEFAULT_MAX_RENEWALS = 2;
    public static final int DEFAULT_RENEWAL_DAYS = 14;
    public static final int DEFAULT_MAX_ACTIVE_LOANS = 5;
    public static final long DEFAULT_FINE_PER_DAY = 5000;
    public static final int DEFAULT_GRACE_DAYS = 0;
    public static final int DEFAULT_RESERVATION_HOLD_DAYS = 3;
    public static final int DEFAULT_DUE_REMINDER_DAYS_BEFORE = 2;
    public static final int DEFAULT_OVERDUE_REMINDER_INTERVAL_DAYS = 3;
    public static final int DEFAULT_OVERDUE_REMINDER_MAX = 3;

    public CirculationPolicy {
        range("Số ngày mượn", loanDays, 1, 365);
        range("Số lần gia hạn tối đa", maxRenewals, 0, 20);
        range("Số ngày mỗi lần gia hạn", renewalDays, 1, 365);
        range("Số sách được mượn cùng lúc", maxActiveLoansPerReader, 1, 100);
        if (finePerDay < 0 || finePerDay > 10_000_000L) {
            throw new InvalidCirculationPolicyException("Mức phạt mỗi ngày phải từ 0 đến 10.000.000");
        }
        range("Số ngày ân hạn", graceDays, 0, 90);
        range("Số ngày giữ chỗ đặt trước", reservationHoldDays, 1, 30);
        range("Số ngày nhắc trước hạn trả", dueReminderDaysBefore, 0, 30);
        range("Chu kỳ nhắc quá hạn (ngày)", overdueReminderIntervalDays, 1, 30);
        range("Số lần nhắc quá hạn tối đa", overdueReminderMax, 0, 20);
    }

    /** Dạng 7 trường (trước khi có cấu hình nhắc hạn): ba trường nhắc hạn lấy mặc định. */
    public CirculationPolicy(int loanDays, int maxRenewals, int renewalDays, int maxActiveLoansPerReader,
                             long finePerDay, int graceDays, int reservationHoldDays) {
        this(loanDays, maxRenewals, renewalDays, maxActiveLoansPerReader, finePerDay, graceDays, reservationHoldDays,
                DEFAULT_DUE_REMINDER_DAYS_BEFORE, DEFAULT_OVERDUE_REMINDER_INTERVAL_DAYS, DEFAULT_OVERDUE_REMINDER_MAX);
    }

    private static void range(String label, int value, int min, int max) {
        if (value < min || value > max) {
            throw new InvalidCirculationPolicyException(label + " phải từ " + min + " đến " + max);
        }
    }

    public static CirculationPolicy defaults() {
        return new CirculationPolicy(DEFAULT_LOAN_DAYS, DEFAULT_MAX_RENEWALS, DEFAULT_RENEWAL_DAYS,
                DEFAULT_MAX_ACTIVE_LOANS, DEFAULT_FINE_PER_DAY, DEFAULT_GRACE_DAYS, DEFAULT_RESERVATION_HOLD_DAYS,
                DEFAULT_DUE_REMINDER_DAYS_BEFORE, DEFAULT_OVERDUE_REMINDER_INTERVAL_DAYS, DEFAULT_OVERDUE_REMINDER_MAX);
    }

    public BigDecimal finePerDayAmount() {
        return BigDecimal.valueOf(finePerDay);
    }
}

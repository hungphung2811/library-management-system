package lms.api.circulation.dto;

import java.time.Instant;

/** Chính sách mượn trả của đơn vị. custom=false: chưa cấu hình, đây là giá trị mặc định (version 0, updatedAt null). */
public record CirculationPolicyView(int loanDays, int maxRenewals, int renewalDays, int maxActiveLoansPerReader,
                                    long finePerDay, int graceDays, int reservationHoldDays,
                                    int dueReminderDaysBefore, int overdueReminderIntervalDays,
                                    int overdueReminderMax, boolean custom, long version, Instant updatedAt) {
}

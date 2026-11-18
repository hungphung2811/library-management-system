package lms.circulation.policy.application;

import jakarta.validation.constraints.NotNull;

/**
 * version: giá trị đã đọc từ GET (0 nếu chưa cấu hình); thiếu thì coi như 0. Khoảng giá trị do domain kiểm.
 * Ba trường nhắc hạn (ADR-0030) không bắt buộc để client cũ vẫn gọi được: thiếu thì giữ giá trị đang lưu (chưa lưu thì mặc định).
 */
public record UpdateCirculationPolicyRequest(@NotNull Integer loanDays, @NotNull Integer maxRenewals,
                                             @NotNull Integer renewalDays, @NotNull Integer maxActiveLoansPerReader,
                                             @NotNull Long finePerDay, @NotNull Integer graceDays,
                                             @NotNull Integer reservationHoldDays, Long version,
                                             Integer dueReminderDaysBefore,
                                             Integer overdueReminderIntervalDays,
                                             Integer overdueReminderMax) {

    /** Dạng 8 trường (client cũ, chưa có cấu hình nhắc hạn). */
    public UpdateCirculationPolicyRequest(Integer loanDays, Integer maxRenewals, Integer renewalDays,
                                          Integer maxActiveLoansPerReader, Long finePerDay, Integer graceDays,
                                          Integer reservationHoldDays, Long version) {
        this(loanDays, maxRenewals, renewalDays, maxActiveLoansPerReader, finePerDay, graceDays, reservationHoldDays,
                version, null, null, null);
    }
}


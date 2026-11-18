package lms.api.circulation.dto;

import jakarta.validation.constraints.NotNull;

/**
 * version: giá trị đọc từ GET (0 nếu chưa cấu hình). Khoảng giá trị hợp lệ do circulation-service kiểm (422).
 * Ba trường nhắc hạn không bắt buộc: thiếu thì circulation giữ giá trị đang lưu.
 */
public record UpdateCirculationPolicyRequest(@NotNull Integer loanDays, @NotNull Integer maxRenewals,
                                             @NotNull Integer renewalDays, @NotNull Integer maxActiveLoansPerReader,
                                             @NotNull Long finePerDay, @NotNull Integer graceDays,
                                             @NotNull Integer reservationHoldDays, Long version,
                                             Integer dueReminderDaysBefore,
                                             Integer overdueReminderIntervalDays,
                                             Integer overdueReminderMax) {
}

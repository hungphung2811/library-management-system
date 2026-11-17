package lms.circulation.policy.application;

import jakarta.validation.constraints.NotNull;

/** version: giá trị đã đọc từ GET (0 nếu chưa cấu hình); thiếu thì coi như 0. Khoảng giá trị do domain kiểm. */
public record UpdateCirculationPolicyRequest(@NotNull Integer loanDays, @NotNull Integer maxRenewals,
                                             @NotNull Integer renewalDays, @NotNull Integer maxActiveLoansPerReader,
                                             @NotNull Long finePerDay, @NotNull Integer graceDays,
                                             @NotNull Integer reservationHoldDays, Long version) {
}

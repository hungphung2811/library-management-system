package lms.api.circulation.dto;

import jakarta.validation.constraints.NotNull;

/** version: giá trị đọc từ GET (0 nếu chưa cấu hình). Khoảng giá trị hợp lệ do circulation-service kiểm (422). */
public record UpdateCirculationPolicyRequest(@NotNull Integer loanDays, @NotNull Integer maxRenewals,
                                             @NotNull Integer renewalDays, @NotNull Integer maxActiveLoansPerReader,
                                             @NotNull Long finePerDay, @NotNull Integer graceDays,
                                             @NotNull Integer reservationHoldDays, Long version) {
}

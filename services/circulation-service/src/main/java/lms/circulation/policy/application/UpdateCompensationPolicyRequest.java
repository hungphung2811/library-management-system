package lms.circulation.policy.application;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lms.circulation.policy.domain.PriceSourceKind;

/**
 * minAmount/maxAmount null = không giới hạn. version: giá trị đã đọc từ GET (0 nếu chưa cấu hình); thiếu thì coi như 0.
 * Khoảng giá trị và sàn lớn hơn trần do domain kiểm (422).
 */
public record UpdateCompensationPolicyRequest(@NotNull BigDecimal lostRate, @NotNull PriceSourceKind priceSource,
                                              BigDecimal minAmount, BigDecimal maxAmount,
                                              @NotNull Integer foundWindowDays, BigDecimal refundProcessingFee,
                                              Long version) {
}

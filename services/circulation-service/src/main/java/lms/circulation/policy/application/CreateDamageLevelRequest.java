package lms.circulation.policy.application;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** code: chữ in hoa, số, gạch dưới, 2-30 ký tự, không đổi được sau khi tạo. active mặc định true, sortOrder mặc định 0. */
public record CreateDamageLevelRequest(@NotBlank String code, @NotBlank String label, @NotNull BigDecimal rate,
                                       Integer sortOrder, Boolean active) {
}

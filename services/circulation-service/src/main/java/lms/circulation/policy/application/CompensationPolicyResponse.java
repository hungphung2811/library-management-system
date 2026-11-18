package lms.circulation.policy.application;

import java.math.BigDecimal;
import java.time.Instant;
import lms.circulation.policy.domain.CompensationPolicy;
import lms.circulation.policy.domain.CompensationPolicySnapshot;

/**
 * custom=false nghĩa là tenant chưa cấu hình: đây là giá trị mặc định (mất 150% giá bìa, không sàn/trần), version 0,
 * updatedAt null. Tiền là VND nguyên.
 */
public record CompensationPolicyResponse(BigDecimal lostRate, String priceSource, BigDecimal minAmount,
                                         BigDecimal maxAmount, int foundWindowDays, BigDecimal refundProcessingFee,
                                         boolean custom, long version, Instant updatedAt) {

    static CompensationPolicyResponse defaults() {
        return of(CompensationPolicy.defaults(), false, 0, null);
    }

    static CompensationPolicyResponse from(CompensationPolicySnapshot s) {
        return of(s.policy(), true, s.version(), s.updatedAt());
    }

    private static CompensationPolicyResponse of(CompensationPolicy p, boolean custom, long version, Instant updatedAt) {
        return new CompensationPolicyResponse(p.lostRate(), p.priceSource().name(), p.minAmount(), p.maxAmount(),
                p.foundWindowDays(), p.refundProcessingFee(), custom, version, updatedAt);
    }
}

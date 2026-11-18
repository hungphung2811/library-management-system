package lms.circulation.policy.application;

import java.time.Instant;
import lms.circulation.policy.domain.CirculationPolicy;
import lms.circulation.policy.domain.CirculationPolicySnapshot;

/** custom=false nghĩa là tenant chưa cấu hình: đây là giá trị mặc định, version 0, updatedAt null. */
public record CirculationPolicyResponse(int loanDays, int maxRenewals, int renewalDays, int maxActiveLoansPerReader,
                                        long finePerDay, int graceDays, int reservationHoldDays,
                                        int dueReminderDaysBefore, int overdueReminderIntervalDays,
                                        int overdueReminderMax, boolean custom, long version, Instant updatedAt) {

    static CirculationPolicyResponse defaults() {
        return of(CirculationPolicy.defaults(), false, 0, null);
    }

    static CirculationPolicyResponse from(CirculationPolicySnapshot s) {
        return of(s.policy(), true, s.version(), s.updatedAt());
    }

    private static CirculationPolicyResponse of(CirculationPolicy p, boolean custom, long version, Instant updatedAt) {
        return new CirculationPolicyResponse(p.loanDays(), p.maxRenewals(), p.renewalDays(),
                p.maxActiveLoansPerReader(), p.finePerDay(), p.graceDays(), p.reservationHoldDays(), p.dueReminderDaysBefore(),
                p.overdueReminderIntervalDays(), p.overdueReminderMax(), custom, version, updatedAt);
    }
}

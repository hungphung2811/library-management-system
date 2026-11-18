package lms.identity.auth.application;

import java.time.Duration;

/** Ngưỡng xác minh email (cấu hình lms.identity.email-verification.*, không hằng số nghiệp vụ trong code). */
public record EmailVerificationPolicy(boolean enabled, Duration otpTtl, int maxAttempts, Duration resendMinInterval,
                                      int maxSendsPerDay) {

    public EmailVerificationPolicy {
        if (otpTtl == null || otpTtl.isNegative() || otpTtl.isZero()) {
            throw new IllegalArgumentException("otpTtl không hợp lệ");
        }
        if (resendMinInterval == null || resendMinInterval.isNegative()) {
            throw new IllegalArgumentException("resendMinInterval không hợp lệ");
        }
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts phải >= 1");
        if (maxSendsPerDay < 1) throw new IllegalArgumentException("maxSendsPerDay phải >= 1");
    }

    public static EmailVerificationPolicy disabled() {
        return new EmailVerificationPolicy(false, Duration.ofMinutes(10), 5, Duration.ofSeconds(60), 5);
    }
}

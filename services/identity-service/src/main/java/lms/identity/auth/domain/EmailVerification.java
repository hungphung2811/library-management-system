package lms.identity.auth.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Trạng thái OTP xác minh email của một người dùng (mỗi người một dòng; gửi lại ghi đè OTP). Chỉ giữ hash + muối của
 * OTP, không bao giờ giữ OTP thô. Quy tắc gửi lại (giãn cách tối thiểu, trần theo cửa sổ 24 giờ) nằm ở đây; quy tắc đếm
 * lần thử sai do tầng persistence thực hiện nguyên tử (xem EmailVerificationPort.reserveAttempt).
 */
public final class EmailVerification {

    /** Cửa sổ tính trần số lần gửi. */
    public static final Duration SEND_WINDOW = Duration.ofHours(24);

    private final UUID userId;
    private final String otpHash;
    private final String otpSalt;
    private final Instant expiresAt;
    private final int attempts;
    private final Instant lastSentAt;
    private final int sendsInWindow;
    private final Instant windowStartedAt;
    private final Instant consumedAt;

    private EmailVerification(UUID userId, String otpHash, String otpSalt, Instant expiresAt, int attempts,
                              Instant lastSentAt, int sendsInWindow, Instant windowStartedAt, Instant consumedAt) {
        if (userId == null || otpHash == null || otpSalt == null || expiresAt == null || lastSentAt == null
                || windowStartedAt == null) {
            throw new IllegalArgumentException("Thiếu dữ liệu xác minh email");
        }
        this.userId = userId;
        this.otpHash = otpHash;
        this.otpSalt = otpSalt;
        this.expiresAt = expiresAt;
        this.attempts = attempts;
        this.lastSentAt = lastSentAt;
        this.sendsInWindow = sendsInWindow;
        this.windowStartedAt = windowStartedAt;
        this.consumedAt = consumedAt;
    }

    /** Lần gửi đầu (lúc đăng ký). */
    public static EmailVerification first(UUID userId, String otpHash, String otpSalt, Instant now, Duration ttl) {
        return new EmailVerification(userId, otpHash, otpSalt, now.plus(ttl), 0, now, 1, now, null);
    }

    public static EmailVerification restore(UUID userId, String otpHash, String otpSalt, Instant expiresAt, int attempts,
                                            Instant lastSentAt, int sendsInWindow, Instant windowStartedAt,
                                            Instant consumedAt) {
        return new EmailVerification(userId, otpHash, otpSalt, expiresAt, attempts, lastSentAt, sendsInWindow,
                windowStartedAt, consumedAt);
    }

    /** Được gửi lại lúc {@code now} không: đủ giãn cách và chưa chạm trần trong cửa sổ hiện tại. */
    public boolean canResend(Instant now, Duration minInterval, int maxPerWindow) {
        if (now.isBefore(lastSentAt.plus(minInterval))) return false;
        if (windowExpired(now)) return true;
        return sendsInWindow < maxPerWindow;
    }

    /** OTP mới: đặt lại số lần thử, tăng bộ đếm gửi (hoặc mở cửa sổ mới khi cửa sổ cũ đã qua). Gọi sau {@link #canResend}. */
    public EmailVerification reissue(String newHash, String newSalt, Instant now, Duration ttl) {
        boolean fresh = windowExpired(now);
        return new EmailVerification(userId, newHash, newSalt, now.plus(ttl), 0, now,
                fresh ? 1 : sendsInWindow + 1, fresh ? now : windowStartedAt, null);
    }

    /** Đã dùng thành công (không dùng lại được). */
    public boolean isConsumed() {
        return consumedAt != null;
    }

    private boolean windowExpired(Instant now) {
        return !now.isBefore(windowStartedAt.plus(SEND_WINDOW));
    }

    public UUID userId() { return userId; }
    public String otpHash() { return otpHash; }
    public String otpSalt() { return otpSalt; }
    public Instant expiresAt() { return expiresAt; }
    public int attempts() { return attempts; }
    public Instant lastSentAt() { return lastSentAt; }
    public int sendsInWindow() { return sendsInWindow; }
    public Instant windowStartedAt() { return windowStartedAt; }
    public Instant consumedAt() { return consumedAt; }
}

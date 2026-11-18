package lms.identity.auth.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Dòng OTP xác minh email (bảng toàn cục email_verifications, V37). Chỉ có hash + muối, không có OTP thô. */
@Entity
@Table(name = "email_verifications")
class EmailVerificationJpaEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "otp_hash", nullable = false, length = 64)
    private String otpHash;

    @Column(name = "otp_salt", nullable = false, length = 64)
    private String otpSalt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_sent_at", nullable = false)
    private Instant lastSentAt;

    @Column(name = "sends_in_window", nullable = false)
    private int sendsInWindow;

    @Column(name = "window_started_at", nullable = false)
    private Instant windowStartedAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected EmailVerificationJpaEntity() {
    }

    EmailVerificationJpaEntity(UUID userId, String otpHash, String otpSalt, Instant expiresAt, int attempts,
                               Instant lastSentAt, int sendsInWindow, Instant windowStartedAt, Instant consumedAt) {
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

    UUID getUserId() { return userId; }
    String getOtpHash() { return otpHash; }
    String getOtpSalt() { return otpSalt; }
    Instant getExpiresAt() { return expiresAt; }
    int getAttempts() { return attempts; }
    Instant getLastSentAt() { return lastSentAt; }
    int getSendsInWindow() { return sendsInWindow; }
    Instant getWindowStartedAt() { return windowStartedAt; }
    Instant getConsumedAt() { return consumedAt; }
}

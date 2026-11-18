package lms.identity.auth.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lms.identity.auth.application.EmailVerificationPort;
import lms.identity.auth.domain.EmailVerification;
import org.springframework.transaction.annotation.Transactional;

/** Persistence xác minh email (bảng toàn cục email_verifications + cột credentials.email_verified_at). */
public class EmailVerificationPersistenceAdapter implements EmailVerificationPort {

    private final EmailVerificationJpaRepository verifications;
    private final CredentialEmailStatusJpaRepository credentials;

    public EmailVerificationPersistenceAdapter(EmailVerificationJpaRepository verifications,
                                               CredentialEmailStatusJpaRepository credentials) {
        this.verifications = verifications;
        this.credentials = credentials;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EmailVerification> find(UUID userId) {
        return verifications.findById(userId).map(e -> EmailVerification.restore(e.getUserId(), e.getOtpHash(),
                e.getOtpSalt(), e.getExpiresAt(), e.getAttempts(), e.getLastSentAt(), e.getSendsInWindow(),
                e.getWindowStartedAt(), e.getConsumedAt()));
    }

    @Override
    @Transactional
    public void save(EmailVerification v) {
        verifications.save(new EmailVerificationJpaEntity(v.userId(), v.otpHash(), v.otpSalt(), v.expiresAt(),
                v.attempts(), v.lastSentAt(), v.sendsInWindow(), v.windowStartedAt(), v.consumedAt()));
    }

    @Override
    @Transactional
    public void markUnverified(UUID userId) {
        credentials.markUnverified(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isVerified(UUID userId) {
        return credentials.findById(userId).map(c -> c.getEmailVerifiedAt() != null).orElse(true);
    }

    @Override
    @Transactional
    public boolean reserveAttempt(UUID userId, Instant now, int maxAttempts) {
        return verifications.reserveAttempt(userId, now, maxAttempts) == 1;
    }

    @Override
    @Transactional
    public boolean complete(UUID userId, Instant now) {
        if (verifications.consume(userId, now) != 1) return false;
        credentials.markVerified(userId, now);
        return true;
    }
}

package lms.identity.auth.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lms.identity.auth.domain.EmailVerification;

/** Lưu trạng thái xác minh email: dòng OTP (email_verifications) và cờ credentials.email_verified_at. */
public interface EmailVerificationPort {

    Optional<EmailVerification> find(UUID userId);

    /** Ghi đè (upsert) dòng OTP của người dùng. */
    void save(EmailVerification verification);

    /** Đặt tài khoản về chưa xác minh (email_verified_at = NULL). */
    void markUnverified(UUID userId);

    /** Tài khoản đã xác minh email chưa. Không có credential thì coi như đã xác minh (không chặn gì). */
    boolean isVerified(UUID userId);

    /**
     * Giữ một lượt thử một cách nguyên tử: tăng attempts chỉ khi dòng OTP chưa dùng, chưa hết hạn và attempts còn dưới
     * {@code maxAttempts}. false = không được thử (hết hạn, hết lượt, đã dùng, không có dòng). Đếm TRƯỚC khi so OTP nên
     * thử đồng thời không vượt trần.
     */
    boolean reserveAttempt(UUID userId, Instant now, int maxAttempts);

    /**
     * Hoàn tất nguyên tử: đánh dấu dòng OTP đã dùng (chỉ khi chưa dùng) và đặt email_verified_at = now. false khi dòng đã
     * bị dùng bởi yêu cầu khác.
     */
    boolean complete(UUID userId, Instant now);
}

package lms.identity.auth.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lms.identity.auth.domain.EmailVerification;
import lms.identity.auth.domain.InvalidEmailVerificationException;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Xác minh email bằng OTP cho tài khoản khách tự đăng ký (audit A3). Công khai nên:
 * <ul>
 *   <li>{@link #verify}: mọi thất bại (không có tài khoản, đã xác minh, OTP sai/hết hạn/hết lượt) cùng một ngoại lệ,
 *       cùng thông điệp.</li>
 *   <li>{@link #resend}: không bao giờ ném lỗi hay trả gì khác nhau (controller luôn 202), kể cả khi chưa đủ giãn
 *       cách, chạm trần theo ngày, tài khoản không tồn tại hay đã xác minh.</li>
 * </ul>
 * Tắt (policy.enabled = false): không tạo OTP, {@link #isVerified} luôn true (hành vi cũ). OTP thô chỉ đi vào thư, sau
 * khi transaction ghi hash đã commit (xem {@link #begin} và {@link #deliver}); không log OTP.
 */
public class EmailVerificationUseCase {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationUseCase.class);

    /** OTP vừa sinh, chờ gửi sau commit. toString không in OTP. */
    public record Challenge(String email, String otp) {
        @Override
        public String toString() {
            return "Challenge[***]";
        }
    }

    private final EmailVerificationPolicy policy;
    private final EmailVerificationPort store;
    private final OtpCodecPort codec;
    private final MailSenderPort mail;
    private final UserContactPort contacts;
    private final UserCredentialRepository credentials;
    private final Clock clock;

    public EmailVerificationUseCase(EmailVerificationPolicy policy, EmailVerificationPort store, OtpCodecPort codec,
                                    MailSenderPort mail, UserContactPort contacts,
                                    UserCredentialRepository credentials, Clock clock) {
        this.policy = policy;
        this.store = store;
        this.codec = codec;
        this.mail = mail;
        this.contacts = contacts;
        this.credentials = credentials;
        this.clock = clock;
    }

    public boolean enabled() {
        return policy.enabled();
    }

    /** Có được đăng nhập storefront không (về mặt email). Tắt tính năng thì luôn true. */
    public boolean isVerified(UUID userId) {
        return !policy.enabled() || store.isVerified(userId);
    }

    /**
     * Đặt tài khoản vừa tạo về chưa xác minh và ghi hash OTP. Gọi TRONG transaction đăng ký (cùng một DB identity), để
     * tài khoản chưa xác minh không bao giờ tồn tại mà thiếu OTP. Trả Challenge để gửi sau commit; rỗng khi tắt.
     */
    public Optional<Challenge> begin(UUID userId, String email) {
        if (!policy.enabled()) return Optional.empty();
        Instant now = clock.instant();
        String otp = codec.generateOtp();
        String salt = codec.newSalt();
        store.markUnverified(userId);
        store.save(EmailVerification.first(userId, codec.hash(salt, userId, otp), salt, now, policy.otpTtl()));
        return Optional.of(new Challenge(email, otp));
    }

    /** Gửi thư chứa OTP (sau commit). Lỗi gửi chỉ log: người dùng bấm gửi lại được. */
    public void deliver(Challenge challenge) {
        try {
            mail.send(challenge.email(), "Mã xác minh email của bạn", body(challenge.otp()));
        } catch (RuntimeException e) {
            log.warn("Không gửi được thư xác minh email: {}", e.getClass().getSimpleName());
        }
    }

    /** identifier là tên đăng nhập hoặc email. Thành công: tài khoản được đánh dấu đã xác minh. */
    public void verify(String identifier, String otp) {
        Instant now = clock.instant();
        Optional<UUID> found = resolve(identifier);
        if (found.isEmpty() || otp == null || otp.isBlank()) throw new InvalidEmailVerificationException();
        UUID userId = found.get();
        if (!store.reserveAttempt(userId, now, policy.maxAttempts())) throw new InvalidEmailVerificationException();
        EmailVerification v = store.find(userId).orElseThrow(InvalidEmailVerificationException::new);
        if (!codec.matches(v.otpSalt(), userId, otp.trim(), v.otpHash())) throw new InvalidEmailVerificationException();
        if (!store.complete(userId, now)) throw new InvalidEmailVerificationException();
    }

    /** Gửi lại OTP. Im lặng bỏ qua mọi trường hợp không đủ điều kiện. */
    public void resend(String identifier) {
        if (!policy.enabled()) return;
        Instant now = clock.instant();
        Optional<UUID> found = resolve(identifier);
        if (found.isEmpty()) return;
        UUID userId = found.get();
        Optional<UserCredential> credential = credentials.findByUserId(userId);
        if (credential.isEmpty() || !credential.get().isActive() || store.isVerified(userId)) return;
        Optional<String> email = contacts.emailOf(userId);
        if (email.isEmpty()) return;

        String otp = codec.generateOtp();
        String salt = codec.newSalt();
        String hash = codec.hash(salt, userId, otp);
        Optional<EmailVerification> current = store.find(userId);
        EmailVerification next;
        if (current.isPresent()) {
            if (!current.get().canResend(now, policy.resendMinInterval(), policy.maxSendsPerDay())) return;
            next = current.get().reissue(hash, salt, now, policy.otpTtl());
        } else {
            next = EmailVerification.first(userId, hash, salt, now, policy.otpTtl());
        }
        store.save(next);
        deliver(new Challenge(email.get(), otp));
    }

    private Optional<UUID> resolve(String identifier) {
        if (identifier == null || identifier.isBlank()) return Optional.empty();
        String normalized = identifier.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("@")) return contacts.userIdByEmail(normalized);
        return credentials.findByUsername(normalized).map(UserCredential::userId);
    }

    private String body(String otp) {
        return "Mã xác minh email của bạn là: " + otp + "\n\nMã có hiệu lực " + policy.otpTtl().toMinutes()
                + " phút. Nếu bạn không đăng ký tài khoản, hãy bỏ qua thư này.";
    }
}

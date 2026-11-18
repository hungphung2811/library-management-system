package lms.identity.auth.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.application.EmailVerificationPolicy;
import lms.identity.auth.application.EmailVerificationPort;
import lms.identity.auth.application.EmailVerificationUseCase;
import lms.identity.auth.application.LoginUseCase;
import lms.identity.auth.application.MailSenderPort;
import lms.identity.auth.application.PasswordHasherPort;
import lms.identity.auth.application.PublishAuthEventPort;
import lms.identity.auth.application.SaveCredentialPort;
import lms.identity.auth.application.TenantAccessPort;
import lms.identity.auth.application.TokenService;
import lms.identity.auth.application.UserContactPort;
import lms.identity.auth.domain.EmailNotVerifiedException;
import lms.identity.auth.domain.EmailVerification;
import lms.identity.auth.domain.InvalidEmailVerificationException;
import lms.identity.auth.domain.TokenChannel;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import lms.identity.auth.infrastructure.security.HmacOtpCodec;
import org.junit.jupiter.api.Test;

/**
 * Xác minh email bằng OTP (audit A3): OTP đúng/sai/hết hạn/quá số lần thử, gửi lại (giãn cách, trần theo ngày), không lộ
 * tài khoản, không lưu OTP thô, tắt tính năng = hành vi cũ, và cổng chặn đăng nhập storefront.
 */
class EmailVerificationUseCaseTest {

    private static final Instant T0 = Instant.parse("2026-11-18T10:00:00Z");

    /** Đồng hồ chỉnh tay. */
    static final class MutableClock extends Clock {
        Instant now = T0;

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    /** Bộ nhớ giả cho cổng lưu trữ, mô phỏng đúng ngữ nghĩa nguyên tử của bản JPA. */
    static final class MemoryStore implements EmailVerificationPort {
        final Map<UUID, EmailVerification> rows = new HashMap<>();
        final Map<UUID, Integer> attempts = new HashMap<>();
        final Map<UUID, Boolean> verified = new HashMap<>();
        final Map<UUID, Instant> consumed = new HashMap<>();

        @Override public Optional<EmailVerification> find(UUID userId) {
            EmailVerification v = rows.get(userId);
            if (v == null) return Optional.empty();
            return Optional.of(EmailVerification.restore(v.userId(), v.otpHash(), v.otpSalt(), v.expiresAt(),
                    attempts.getOrDefault(userId, 0), v.lastSentAt(), v.sendsInWindow(), v.windowStartedAt(),
                    consumed.get(userId)));
        }
        @Override public void save(EmailVerification v) {
            rows.put(v.userId(), v);
            attempts.put(v.userId(), v.attempts());
            consumed.remove(v.userId());
        }
        @Override public void markUnverified(UUID userId) { verified.put(userId, false); }
        @Override public boolean isVerified(UUID userId) { return verified.getOrDefault(userId, true); }
        @Override public boolean reserveAttempt(UUID userId, Instant now, int max) {
            EmailVerification v = rows.get(userId);
            if (v == null || consumed.containsKey(userId) || !v.expiresAt().isAfter(now)
                    || attempts.getOrDefault(userId, 0) >= max) return false;
            attempts.merge(userId, 1, Integer::sum);
            return true;
        }
        @Override public boolean complete(UUID userId, Instant now) {
            if (consumed.containsKey(userId)) return false;
            consumed.put(userId, now);
            verified.put(userId, true);
            return true;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final MemoryStore store = new MemoryStore();
    private final HmacOtpCodec codec = new HmacOtpCodec("test-secret");
    private final List<String[]> sent = new ArrayList<>();
    private final MailSenderPort mail = (to, subject, body) -> sent.add(new String[] {to, subject, body});
    private final UserCredentialRepository credentials = mock(UserCredentialRepository.class);
    private final UserContactPort contacts = mock(UserContactPort.class);
    private final UUID userId = UUID.randomUUID();

    private final EmailVerificationPolicy policy =
            new EmailVerificationPolicy(true, Duration.ofMinutes(10), 3, Duration.ofSeconds(60), 3);
    private final EmailVerificationUseCase useCase =
            new EmailVerificationUseCase(policy, store, codec, mail, contacts, credentials, clock);

    private UserCredential credential(boolean active) {
        UserCredential c = UserCredential.register(userId, "bob", "hash", null);
        if (!active) c.deactivate();
        return c;
    }

    private String registerAndGetOtp() {
        when(credentials.findByUsername("bob")).thenReturn(Optional.of(credential(true)));
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential(true)));
        when(contacts.userIdByEmail("bob@example.com")).thenReturn(Optional.of(userId));
        when(contacts.emailOf(userId)).thenReturn(Optional.of("bob@example.com"));
        EmailVerificationUseCase.Challenge c = useCase.begin(userId, "bob@example.com").orElseThrow();
        return c.otp();
    }

    @Test
    void beginTaoTaiKhoanChuaXacMinhVaChiLuuHashKhongLuuOtpTho() {
        String otp = registerAndGetOtp();

        assertThat(otp).matches("\\d{6}");
        assertThat(useCase.isVerified(userId)).isFalse();
        EmailVerification row = store.rows.get(userId);
        assertThat(row.otpHash()).isNotEqualTo(otp).doesNotContain(otp).hasSize(64);
        assertThat(row.expiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(10)));
        assertThat(sent).isEmpty();
    }

    @Test
    void otpDungXacMinhBangUsernameHoacEmailVaChiDungDuocMotLan() {
        String otp = registerAndGetOtp();

        useCase.verify("BOB", otp);

        assertThat(useCase.isVerified(userId)).isTrue();
        assertThatThrownBy(() -> useCase.verify("bob@example.com", otp)).isInstanceOf(InvalidEmailVerificationException.class);
    }

    @Test
    void xacMinhBangEmailThanhCong() {
        String otp = registerAndGetOtp();

        useCase.verify(" Bob@Example.com ", otp);

        assertThat(useCase.isVerified(userId)).isTrue();
    }

    @Test
    void otpSaiBiTuChoiVaTinhVaoSoLanThu() {
        String otp = registerAndGetOtp();
        String wrong = otp.equals("000000") ? "111111" : "000000";

        assertThatThrownBy(() -> useCase.verify("bob", wrong)).isInstanceOf(InvalidEmailVerificationException.class);

        assertThat(store.attempts.get(userId)).isEqualTo(1);
        assertThat(useCase.isVerified(userId)).isFalse();
    }

    @Test
    void quaSoLanThuSaiThiOtpDungCungBiVoHieu() {
        String otp = registerAndGetOtp();
        String wrong = otp.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> useCase.verify("bob", wrong)).isInstanceOf(InvalidEmailVerificationException.class);
        }

        assertThatThrownBy(() -> useCase.verify("bob", otp)).isInstanceOf(InvalidEmailVerificationException.class);
        assertThat(useCase.isVerified(userId)).isFalse();
        assertThat(store.attempts.get(userId)).isEqualTo(3);
    }

    @Test
    void otpHetHanBiTuChoi() {
        String otp = registerAndGetOtp();
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));

        assertThatThrownBy(() -> useCase.verify("bob", otp)).isInstanceOf(InvalidEmailVerificationException.class);
        assertThat(useCase.isVerified(userId)).isFalse();
    }

    @Test
    void moiLyDoSaiCungMotNgoaiLeVaMotThongDiep() {
        String otp = registerAndGetOtp();
        when(credentials.findByUsername("ghost")).thenReturn(Optional.empty());
        when(contacts.userIdByEmail("ghost@example.com")).thenReturn(Optional.empty());
        String wrong = otp.equals("000000") ? "111111" : "000000";

        List<String> messages = new ArrayList<>();
        for (Runnable r : List.<Runnable>of(
                () -> useCase.verify("ghost", "123456"),
                () -> useCase.verify("ghost@example.com", "123456"),
                () -> useCase.verify("bob", wrong),
                () -> useCase.verify("", "123456"),
                () -> useCase.verify(null, "123456"),
                () -> useCase.verify("bob", " "))) {
            try {
                r.run();
            } catch (InvalidEmailVerificationException e) {
                messages.add(e.getMessage());
            }
        }

        assertThat(messages).hasSize(6).containsOnly(InvalidEmailVerificationException.MESSAGE);
    }

    @Test
    void resendBiBoQuaTrongKhoangGianCachToiThieu() {
        registerAndGetOtp();
        clock.advance(Duration.ofSeconds(30));

        useCase.resend("bob");

        assertThat(sent).isEmpty();
    }

    @Test
    void resendSauGianCachGuiOtpMoiVaOtpCuHetTacDung() {
        String first = registerAndGetOtp();
        clock.advance(Duration.ofSeconds(61));

        useCase.resend("bob");

        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)[0]).isEqualTo("bob@example.com");
        String second = sent.get(0)[2].replaceAll("(?s).*?(\\d{6}).*", "$1");
        assertThat(second).matches("\\d{6}");
        assertThat(store.attempts.get(userId)).isZero();
        if (!second.equals(first)) {
            assertThatThrownBy(() -> useCase.verify("bob", first)).isInstanceOf(InvalidEmailVerificationException.class);
        }
        // verify() ở trên đã tiêu một lượt; OTP mới vẫn dùng được
        useCase.verify("bob", second);
        assertThat(useCase.isVerified(userId)).isTrue();
    }

    @Test
    void resendChamTranTheoNgayThiBoQuaYenLang() {
        registerAndGetOtp(); // lần 1 (lúc đăng ký)
        for (int i = 0; i < 2; i++) { // lần 2, 3
            clock.advance(Duration.ofSeconds(61));
            useCase.resend("bob");
        }
        assertThat(sent).hasSize(2);

        clock.advance(Duration.ofSeconds(61));
        useCase.resend("bob"); // lần 4 > trần 3/ngày

        assertThat(sent).hasSize(2);

        clock.advance(Duration.ofHours(24)); // cửa sổ mới
        useCase.resend("bob");
        assertThat(sent).hasSize(3);
    }

    @Test
    void resendKhongLoTaiKhoan() {
        registerAndGetOtp();
        when(credentials.findByUsername("ghost")).thenReturn(Optional.empty());
        when(contacts.userIdByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatCode(() -> {
            useCase.resend("ghost");
            useCase.resend("ghost@example.com");
            useCase.resend("");
            useCase.resend(null);
        }).doesNotThrowAnyException();
        assertThat(sent).isEmpty();
    }

    @Test
    void resendBoQuaTaiKhoanDaXacMinhHoacBiVoHieu() {
        String otp = registerAndGetOtp();
        useCase.verify("bob", otp);
        clock.advance(Duration.ofMinutes(5));

        useCase.resend("bob");
        assertThat(sent).isEmpty();

        store.verified.put(userId, false);
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential(false)));
        useCase.resend("bob");
        assertThat(sent).isEmpty();
    }

    @Test
    void loiGuiThuKhongLamHongResendHoacBegin() {
        MailSenderPort broken = (to, s, b) -> { throw new IllegalStateException("smtp down"); };
        EmailVerificationUseCase uc = new EmailVerificationUseCase(policy, store, codec, broken, contacts, credentials,
                clock);

        assertThatCode(() -> uc.deliver(new EmailVerificationUseCase.Challenge("a@b.c", "123456")))
                .doesNotThrowAnyException();
    }

    @Test
    void tatTinhNangThiTaiKhoanMoiCoiNhuDaXacMinhVaKhongTaoOtp() {
        EmailVerificationUseCase off = new EmailVerificationUseCase(EmailVerificationPolicy.disabled(), store, codec,
                mail, contacts, credentials, clock);

        assertThat(off.begin(userId, "bob@example.com")).isEmpty();
        store.verified.put(userId, false); // dù cờ DB có NULL, tắt tính năng thì không chặn
        assertThat(off.isVerified(userId)).isTrue();
        off.resend("bob");
        assertThat(sent).isEmpty();
        assertThat(store.rows).isEmpty();
    }

    @Test
    void hmacCoMuoiRiengVaSoKhopThoiGianHang() {
        UUID id = UUID.randomUUID();
        String s1 = codec.newSalt();
        String s2 = codec.newSalt();

        assertThat(codec.hash(s1, id, "123456")).isNotEqualTo(codec.hash(s2, id, "123456"));
        assertThat(codec.matches(s1, id, "123456", codec.hash(s1, id, "123456"))).isTrue();
        assertThat(codec.matches(s1, id, "123457", codec.hash(s1, id, "123456"))).isFalse();
        assertThat(codec.matches(s1, UUID.randomUUID(), "123456", codec.hash(s1, id, "123456"))).isFalse();
    }

    // ---- Cổng chặn đăng nhập storefront ----

    private LoginUseCase loginWith(EmailVerificationUseCase verification, TokenService tokens) {
        UserCredentialRepository repo = mock(UserCredentialRepository.class);
        when(repo.findByUsername("bob")).thenReturn(Optional.of(credential(true)));
        PasswordHasherPort hasher = mock(PasswordHasherPort.class);
        when(hasher.matches(any(), any())).thenReturn(true);
        return new LoginUseCase(repo, mock(SaveCredentialPort.class), hasher, tokens, mock(PublishAuthEventPort.class),
                clock, mock(TenantAccessPort.class), verification);
    }

    @Test
    void dangNhapStorefrontBiChanKhiChuaXacMinhNhungChiSauKhiMatKhauDung() {
        registerAndGetOtp();
        TokenService tokens = mock(TokenService.class);
        LoginUseCase login = loginWith(useCase, tokens);

        assertThatThrownBy(() -> login.execute("bob", "pw", "shop", TokenChannel.STOREFRONT))
                .isInstanceOf(EmailNotVerifiedException.class);
        verify(tokens, never()).issueFor(any(), any(), any(), any());
    }

    @Test
    void dangNhapNhanVienKhongBiChanVaDaXacMinhThiVaoDuoc() {
        String otp = registerAndGetOtp();
        TokenService tokens = mock(TokenService.class);
        TokenResponse response = mock(TokenResponse.class);
        when(tokens.issueFor(any(), any(), any(), any())).thenReturn(response);
        LoginUseCase login = loginWith(useCase, tokens);

        assertThat(login.execute("bob", "pw", null, TokenChannel.STAFF, null)).isSameAs(response);

        useCase.verify("bob", otp);
        assertThat(login.execute("bob", "pw", "shop", TokenChannel.STOREFRONT, null)).isSameAs(response);
    }
}

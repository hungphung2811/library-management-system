package lms.identity.auth.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.identity.auth.application.EmailVerificationPolicy;
import lms.identity.auth.application.EmailVerificationPort;
import lms.identity.auth.application.EmailVerificationUseCase;
import lms.identity.auth.application.SignupAccountPort;
import lms.identity.auth.application.SignupRolePort;
import lms.identity.auth.application.SignupUseCase;
import lms.identity.auth.application.SignupUseCase.Command;
import lms.identity.auth.application.StorefrontTenantPort;
import lms.identity.auth.application.UserContactPort;
import lms.identity.auth.domain.EmailVerification;
import lms.identity.auth.domain.SignupNotAvailableException;
import lms.identity.auth.domain.UserCredentialRepository;
import lms.identity.auth.infrastructure.security.HmacOtpCodec;
import lms.identity.tenant.application.StorefrontTenant;
import lms.identity.tenant.application.TransactionalRunner;
import org.junit.jupiter.api.Test;

/** Tự đăng ký + xác minh email: tài khoản chưa xác minh ghi cùng transaction, thư chỉ gửi sau commit, tắt = hành vi cũ. */
class SignupEmailVerificationTest {

    private final UUID newUser = UUID.randomUUID();
    private final List<String> events = new ArrayList<>();
    private boolean roleAvailable = true;

    private final StorefrontTenantPort tenants = code -> Optional.of(
            new StorefrontTenant(UUID.randomUUID(), "shop", "Shop", List.of("SALES")));
    private final SignupAccountPort accounts = (u, p, n, e) -> {
        events.add("register");
        return newUser;
    };
    private final SignupRolePort roles = new SignupRolePort() {
        public boolean available() { return roleAvailable; }
        public void assignTo(UUID userId) { events.add("assign"); }
    };
    /** Giao dịch giả: ghi "commit" sau khi chạy xong khối lệnh. */
    private final TransactionalRunner tx = new TransactionalRunner() {
        @Override
        public <T> T inTransaction(java.util.function.Supplier<T> work) {
            T result = work.get();
            events.add("commit");
            return result;
        }
    };

    private final EmailVerificationPort store = new EmailVerificationPort() {
        public Optional<EmailVerification> find(UUID userId) { return Optional.empty(); }
        public void save(EmailVerification v) { events.add("saveOtp"); }
        public void markUnverified(UUID userId) { events.add("markUnverified"); }
        public boolean isVerified(UUID userId) { return false; }
        public boolean reserveAttempt(UUID userId, java.time.Instant now, int max) { return false; }
        public boolean complete(UUID userId, java.time.Instant now) { return false; }
    };

    private EmailVerificationUseCase verification(boolean enabled) {
        EmailVerificationPolicy policy = enabled
                ? new EmailVerificationPolicy(true, Duration.ofMinutes(10), 5, Duration.ofSeconds(60), 5)
                : EmailVerificationPolicy.disabled();
        return new EmailVerificationUseCase(policy, store, new HmacOtpCodec("s"),
                (to, subject, body) -> events.add("mail:" + to), mock(UserContactPort.class),
                mock(UserCredentialRepository.class), Clock.systemUTC());
    }

    private static Command command() {
        return new Command("shop", "bob", "secret123", "Bob", "bob@example.com");
    }

    @Test
    void batTinhNangTaoTaiKhoanChuaXacMinhGhiOtpTrongTransactionRoiMoiGuiThu() {
        SignupUseCase useCase = new SignupUseCase(tenants, accounts, roles, tx, verification(true));

        assertThat(useCase.execute(command())).isEqualTo(newUser);

        assertThat(events).containsExactly("register", "assign", "markUnverified", "saveOtp", "commit",
                "mail:bob@example.com");
    }

    @Test
    void tatTinhNangKhongTaoOtpVaKhongGuiThu() {
        SignupUseCase useCase = new SignupUseCase(tenants, accounts, roles, tx, verification(false));

        useCase.execute(command());

        assertThat(events).containsExactly("register", "assign", "commit");
    }

    @Test
    void khongCoBoXacMinhThiHanhViCu() {
        SignupUseCase useCase = new SignupUseCase(tenants, accounts, roles, tx);

        useCase.execute(command());

        assertThat(events).containsExactly("register", "assign", "commit");
    }

    @Test
    void dangKyThatBaiThiKhongGuiThu() {
        roleAvailable = false;
        SignupUseCase useCase = new SignupUseCase(tenants, accounts, roles, tx, verification(true));

        assertThatThrownBy(() -> useCase.execute(command())).isInstanceOf(SignupNotAvailableException.class);

        assertThat(events).noneMatch(e -> e.startsWith("mail:") || e.equals("saveOtp"));
    }
}

package lms.identity.auth.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lms.identity.auth.domain.AccountLockedEvent;
import lms.identity.auth.domain.LoginFailedEvent;
import lms.identity.auth.domain.LoginSucceededEvent;
import lms.identity.auth.domain.AccountDisabledException;
import lms.identity.auth.domain.AccountLockedException;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.InvalidRefreshTokenException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserCredentialTest {

    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    private UserCredential credential() {
        return UserCredential.register(UUID.randomUUID(), "alice", "hash", null);
    }

    @Test
    void khoaTaiKhoanSauNamLanSaiVaMoLaiSauHetHan() {
        UserCredential c = credential();

        for (int i = 0; i < UserCredential.MAX_FAILED_ATTEMPTS; i++) c.recordFailure(now);

        assertThat(c.isLocked(now)).isTrue();
        assertThatThrownBy(() -> c.ensureCanLogin(now.plusSeconds(60))).isInstanceOf(AccountLockedException.class);
        assertThat(c.pullEvents()).hasAtLeastOneElementOfType(AccountLockedEvent.class);

        Instant later = now.plus(UserCredential.LOCK_DURATION).plusSeconds(1);
        assertThat(c.isLocked(later)).isFalse();
        c.ensureCanLogin(later);
    }

    @Test
    void dangNhapThanhCongXoaBoDemSaiVaPhatEvent() {
        UserCredential c = credential();
        c.recordFailure(now);
        c.recordFailure(now);

        c.recordSuccess(now);

        assertThat(c.failedAttempts()).isZero();
        var events = c.pullEvents();
        assertThat(events).hasAtLeastOneElementOfType(LoginFailedEvent.class);
        assertThat(events).hasAtLeastOneElementOfType(LoginSucceededEvent.class);
    }

    @Test
    void taiKhoanVoHieuHoaKhongDangNhapDuoc() {
        UserCredential c = credential();
        c.deactivate();

        assertThatThrownBy(() -> c.ensureCanLogin(now)).isInstanceOf(AccountDisabledException.class);
    }

    @Test
    void refreshTokenHetHanHoacBiThuHoiKhongDungDuoc() {
        RefreshToken token = RefreshToken.issue(UUID.randomUUID(), "h", now, Duration.ofDays(7));

        token.ensureUsable(now.plusSeconds(1));
        assertThatThrownBy(() -> token.ensureUsable(now.plus(Duration.ofDays(8))))
                .isInstanceOf(InvalidRefreshTokenException.class);

        token.revoke();
        assertThatThrownBy(() -> token.ensureUsable(now.plusSeconds(1))).isInstanceOf(InvalidRefreshTokenException.class);
    }
}

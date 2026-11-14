package lms.identity.auth.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.application.PasswordHasherPort;
import lms.identity.auth.application.PublishAuthEventPort;
import lms.identity.auth.application.RefreshTokenCodecPort;
import lms.identity.auth.application.SaveCredentialPort;
import lms.identity.auth.application.SaveRefreshTokenPort;
import lms.identity.auth.application.TokenIssuerPort;
import lms.identity.auth.application.UserAccessPort;
import lms.identity.auth.application.LoginUseCase;
import lms.identity.auth.application.RefreshTokenUseCase;
import lms.identity.auth.application.RegisterCredentialUseCase;
import lms.identity.auth.application.TokenService;
import lms.identity.auth.domain.AccountLockedException;
import lms.identity.auth.domain.DuplicateUsernameException;
import lms.identity.auth.domain.InvalidCredentialsException;
import lms.identity.auth.domain.InvalidRefreshTokenException;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.RefreshTokenRepository;
import lms.identity.auth.domain.UserCredentialRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuthUseCasesTest {

    private final UserCredentialRepository credentials = mock(UserCredentialRepository.class);
    private final SaveCredentialPort saveCredential = mock(SaveCredentialPort.class);
    private final RefreshTokenRepository refreshRepo = mock(RefreshTokenRepository.class);
    private final SaveRefreshTokenPort saveRefresh = mock(SaveRefreshTokenPort.class);
    private final PasswordHasherPort hasher = mock(PasswordHasherPort.class);
    private final PublishAuthEventPort events = mock(PublishAuthEventPort.class);
    private final UserAccessPort access = mock(UserAccessPort.class);
    private final TokenIssuerPort issuer = mock(TokenIssuerPort.class);
    private final RefreshTokenCodecPort codec = mock(RefreshTokenCodecPort.class);
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    private final TokenService tokenService =
            new TokenService(access, issuer, codec, saveRefresh, clock, Duration.ofDays(7));
    private final LoginUseCase login = new LoginUseCase(credentials, saveCredential, hasher, tokenService, events, clock);
    private final RefreshTokenUseCase refresh =
            new RefreshTokenUseCase(refreshRepo, saveRefresh, credentials, codec, tokenService, clock);

    private final UUID userId = UUID.randomUUID();
    private UserCredential credential = UserCredential.register(userId, "alice", "hash", null);

    private void stubIssue() {
        when(access.fetch(userId)).thenReturn(new UserAccessPort.UserAccess(userId, List.of("MEMBER"), List.of("BORROW_BOOK")));
        when(issuer.issue(any())).thenReturn(new TokenIssuerPort.IssuedToken("access-jwt", 900));
        when(codec.generate()).thenReturn("raw-refresh");
        when(codec.hash("raw-refresh")).thenReturn("hash-refresh");
    }

    @Test
    void dangNhapDungPhatHanhTokenKemRolesVaPermissions() {
        stubIssue();
        when(credentials.findByUsername("alice")).thenReturn(Optional.of(credential));
        when(hasher.matches("pw", "hash")).thenReturn(true);

        TokenResponse response = login.execute(" Alice ", "pw");

        assertThat(response.accessToken()).isEqualTo("access-jwt");
        assertThat(response.refreshToken()).isEqualTo("raw-refresh");
        ArgumentCaptor<TokenIssuerPort.Claims> claims = ArgumentCaptor.forClass(TokenIssuerPort.Claims.class);
        verify(issuer).issue(claims.capture());
        assertThat(claims.getValue().permissions()).containsExactly("BORROW_BOOK");
        verify(saveRefresh).save(any(RefreshToken.class));
        verify(events).publish(any());
    }

    @Test
    void saiMatKhauGhiNhanLanSaiVaKhongPhatHanhToken() {
        when(credentials.findByUsername("alice")).thenReturn(Optional.of(credential));
        when(hasher.matches("sai", "hash")).thenReturn(false);

        assertThatThrownBy(() -> login.execute("alice", "sai")).isInstanceOf(InvalidCredentialsException.class);

        assertThat(credential.failedAttempts()).isEqualTo(1);
        verify(saveCredential).save(credential);
        verify(issuer, never()).issue(any());
    }

    @Test
    void usernameKhongTonTaiCungBaoSaiThongTin() {
        when(credentials.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> login.execute("ghost", "pw")).isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void taiKhoanDangBiKhoaThiKhongSoKhopMatKhau() {
        for (int i = 0; i < UserCredential.MAX_FAILED_ATTEMPTS; i++) credential.recordFailure(now);
        when(credentials.findByUsername("alice")).thenReturn(Optional.of(credential));

        assertThatThrownBy(() -> login.execute("alice", "pw")).isInstanceOf(AccountLockedException.class);

        verify(hasher, never()).matches(any(), any());
    }

    @Test
    void refreshXoayVongThuHoiTokenCuVaPhatHanhTokenMoi() {
        stubIssue();
        when(codec.hash("old-raw")).thenReturn("old-hash");
        RefreshToken old = RefreshToken.issue(userId, "old-hash", now.minusSeconds(60), Duration.ofDays(7));
        when(refreshRepo.findByTokenHash("old-hash")).thenReturn(Optional.of(old));
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential));

        TokenResponse response = refresh.execute("old-raw");

        assertThat(old.isRevoked()).isTrue();
        assertThat(response.refreshToken()).isEqualTo("raw-refresh");
    }

    @Test
    void dungLaiRefreshTokenCuThuHoiMoiPhienCuaNguoiDung() {
        when(codec.hash("stolen")).thenReturn("stolen-hash");
        RefreshToken used = RefreshToken.issue(userId, "stolen-hash", now.minusSeconds(60), Duration.ofDays(7));
        used.revoke();
        when(refreshRepo.findByTokenHash("stolen-hash")).thenReturn(Optional.of(used));

        assertThatThrownBy(() -> refresh.execute("stolen")).isInstanceOf(InvalidRefreshTokenException.class);

        verify(saveRefresh).revokeAllByUserId(userId);
        verify(issuer, never()).issue(any());
    }

    @Test
    void dangKyChuanHoaUsernameVaChanTrung() {
        RegisterCredentialUseCase register = new RegisterCredentialUseCase(credentials, saveCredential, hasher);
        when(hasher.hash("password1")).thenReturn("bcrypt");
        when(saveCredential.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UUID id = UUID.randomUUID();
        assertThat(register.execute(id, "  Bob ", "password1", null)).isEqualTo(id);

        when(credentials.existsByUsername("bob")).thenReturn(true);
        assertThatThrownBy(() -> register.execute(UUID.randomUUID(), "BOB", "password1", null))
                .isInstanceOf(DuplicateUsernameException.class);
        assertThatThrownBy(() -> register.execute(UUID.randomUUID(), "carol", "ngan", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

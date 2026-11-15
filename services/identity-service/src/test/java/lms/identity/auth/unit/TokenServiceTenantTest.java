package lms.identity.auth.unit;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.application.RefreshTokenCodecPort;
import lms.identity.auth.application.RefreshTokenUseCase;
import lms.identity.auth.application.RegisterCredentialUseCase;
import lms.identity.auth.application.SaveCredentialPort;
import lms.identity.auth.application.SaveRefreshTokenPort;
import lms.identity.auth.application.SwitchTenantSessionUseCase;
import lms.identity.auth.application.TenantAccessPort;
import lms.identity.auth.application.TokenIssuerPort;
import lms.identity.auth.application.TokenService;
import lms.identity.auth.application.UserAccessPort;
import lms.identity.auth.application.PasswordHasherPort;
import lms.identity.auth.domain.InvalidRefreshTokenException;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.RefreshTokenRepository;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import lms.identity.tenant.application.TenantChoice;
import lms.identity.tenant.application.TenantSummary;
import lms.identity.tenant.domain.NoTenantAccessException;
import org.junit.jupiter.api.Test;

/** Token theo tenant: claim tenantId/platformAdmin, refresh giữ tenant, đổi tenant. */
class TokenServiceTenantTest {

    private final Instant now = Instant.parse("2026-11-15T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final UUID userId = UUID.randomUUID();
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final TenantSummary summaryA = new TenantSummary(tenantA, "lib-a", "Thư viện A");
    private final TenantSummary summaryB = new TenantSummary(tenantB, "lib-b", "Thư viện B");

    private final List<TokenIssuerPort.Claims> issued = new ArrayList<>();
    private final List<RefreshToken> savedRefresh = new ArrayList<>();
    private final TenantAccessPort tenants = mock(TenantAccessPort.class);
    private final RefreshTokenRepository refreshRepo = mock(RefreshTokenRepository.class);
    private final SaveRefreshTokenPort refreshSave = new SaveRefreshTokenPort() {
        public RefreshToken save(RefreshToken t) { savedRefresh.add(t); return t; }
        public void revokeAllByUserId(UUID u) { }
    };
    private final UserCredentialRepository credentials = mock(UserCredentialRepository.class);
    private final RefreshTokenCodecPort codec = new RefreshTokenCodecPort() {
        public String generate() { return "raw-new"; }
        public String hash(String raw) { return "h-" + raw; }
    };

    private final TokenService service = new TokenService(
            id -> new UserAccessPort.UserAccess(id, List.of("LIBRARIAN"), List.of("BorrowingController/borrow"), true),
            c -> { issued.add(c); return new TokenIssuerPort.IssuedToken("jwt", 900); },
            codec, refreshSave, clock, Duration.ofDays(7), id -> Optional.empty(), tenants);

    private UserCredential credential(boolean mustChange, boolean platformAdmin) {
        return UserCredential.restore(userId, "alice", "h", null, true, 0, null, mustChange, platformAdmin);
    }

    @Test
    void tokenChuaTenantIdVaDanhSachTenantTraVe() {
        when(tenants.selectForLogin(userId, "lib-a", false)).thenReturn(new TenantChoice(tenantA, List.of(summaryA, summaryB)));

        TokenResponse response = service.issueFor(credential(false, false), "lib-a");

        TokenIssuerPort.Claims claims = issued.get(0);
        assertThat(claims.tenantId()).isEqualTo(tenantA);
        assertThat(claims.platformAdmin()).isFalse();
        // 4a: permissions/admin tính như trước (role còn toàn cục)
        assertThat(claims.permissions()).containsExactly("BorrowingController/borrow");
        assertThat(claims.admin()).isTrue();
        assertThat(response.tenantId()).isEqualTo(tenantA);
        assertThat(response.availableTenants()).containsExactly(summaryA, summaryB);
        assertThat(savedRefresh.get(0).tenantId()).isEqualTo(tenantA);
        assertThat(savedRefresh.get(0).platformSession()).isFalse();
    }

    @Test
    void tokenPhaiDoiMatKhauCoTenantIdNhungKhongCoPermissionAdminHayPlatformAdmin() {
        when(tenants.selectForLogin(userId, null, true)).thenReturn(new TenantChoice(tenantA, List.of(summaryA)));

        TokenResponse response = service.issueFor(credential(true, true));

        TokenIssuerPort.Claims claims = issued.get(0);
        assertThat(response.mustChangePassword()).isTrue();
        assertThat(claims.tenantId()).isEqualTo(tenantA);
        assertThat(claims.permissions()).isEmpty();
        assertThat(claims.admin()).isFalse();
        assertThat(claims.platformAdmin()).isFalse();
    }

    @Test
    void platformAdminKhongCoTenantNhanTokenPlatformAdminKhongTenantId() {
        when(tenants.selectForLogin(userId, null, true)).thenReturn(new TenantChoice(null, List.of()));

        TokenResponse response = service.issueFor(credential(false, true));

        assertThat(issued.get(0).platformAdmin()).isTrue();
        assertThat(issued.get(0).tenantId()).isNull();
        assertThat(response.tenantId()).isNull();
        assertThat(response.availableTenants()).isEmpty();
        assertThat(savedRefresh.get(0).tenantId()).isNull();
        assertThat(savedRefresh.get(0).platformSession()).isTrue();
    }

    @Test
    void khongCoTenantNaoThiKhongCapToken() {
        when(tenants.selectForLogin(userId, null, false)).thenThrow(new NoTenantAccessException());

        assertThatThrownBy(() -> service.issueFor(credential(false, false))).isInstanceOf(NoTenantAccessException.class);
        assertThatThrownBy(() -> service.issueForExternal(credential(false, false)))
                .isInstanceOf(NoTenantAccessException.class);
        assertThat(issued).isEmpty();
        assertThat(savedRefresh).isEmpty();
    }

    @Test
    void dangNhapNgoaiChonTenantQuaCungUseCase() {
        when(tenants.selectForLogin(userId, null, false)).thenReturn(new TenantChoice(tenantB, List.of(summaryB)));

        service.issueForExternal(credential(true, false));

        assertThat(issued.get(0).tenantId()).isEqualTo(tenantB);
        assertThat(issued.get(0).mustChangePassword()).isFalse();
    }

    // ---- refresh ----

    private RefreshTokenUseCase refresh() {
        return new RefreshTokenUseCase(refreshRepo, refreshSave, credentials, codec, service, tenants, clock);
    }

    private RefreshToken stored(RefreshToken token) {
        when(refreshRepo.findByTokenHash(token.tokenHash())).thenReturn(Optional.of(token));
        return token;
    }

    @Test
    void refreshGiuNguyenTenantSauKhiKiemTraLaiQuyen() {
        RefreshToken old = stored(RefreshToken.issueForTenant(userId, tenantB, "h-raw-old", now.minusSeconds(60), Duration.ofDays(7)));
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential(false, false)));
        when(tenants.verify(userId, tenantB)).thenReturn(new TenantChoice(tenantB, List.of(summaryA, summaryB)));

        TokenResponse response = refresh().execute("raw-old");

        assertThat(old.isRevoked()).isTrue();
        assertThat(issued.get(0).tenantId()).isEqualTo(tenantB);
        assertThat(response.tenantId()).isEqualTo(tenantB);
        assertThat(savedRefresh).extracting(RefreshToken::tenantId).contains(tenantB);
        verify(tenants, never()).selectForLogin(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void refreshKhiTenantTamNgungHoacMembershipTatBiTuChoiVaKhongXoayVong() {
        RefreshToken old = stored(RefreshToken.issueForTenant(userId, tenantB, "h-raw-old", now.minusSeconds(60), Duration.ofDays(7)));
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential(false, false)));
        when(tenants.verify(userId, tenantB)).thenThrow(new NoTenantAccessException());

        assertThatThrownBy(() -> refresh().execute("raw-old")).isInstanceOf(NoTenantAccessException.class);

        assertThat(old.isRevoked()).isFalse();
        assertThat(issued).isEmpty();
    }

    @Test
    void refreshTokenCuKhongGanTenantBiTuChoi() {
        stored(RefreshToken.issue(userId, "h-raw-legacy", now.minusSeconds(60), Duration.ofDays(7)));
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential(false, true)));

        assertThatThrownBy(() -> refresh().execute("raw-legacy")).isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(issued).isEmpty();
    }

    @Test
    void refreshPhienNenTangChiKhiCredentialConPlatformAdmin() {
        stored(RefreshToken.issueForPlatform(userId, "h-raw-p1", now.minusSeconds(60), Duration.ofDays(7)));
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential(false, true)));
        when(tenants.available(userId)).thenReturn(List.of());

        refresh().execute("raw-p1");
        assertThat(issued.get(0).platformAdmin()).isTrue();
        assertThat(issued.get(0).tenantId()).isNull();
        assertThat(savedRefresh.get(savedRefresh.size() - 1).platformSession()).isTrue();

        stored(RefreshToken.issueForPlatform(userId, "h-raw-p2", now.minusSeconds(60), Duration.ofDays(7)));
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential(false, false)));
        assertThatThrownBy(() -> refresh().execute("raw-p2")).isInstanceOf(InvalidRefreshTokenException.class);
    }

    // ---- đổi tenant ----

    private SwitchTenantSessionUseCase switchTenant() {
        return new SwitchTenantSessionUseCase(credentials, refreshRepo, refreshSave, codec, tenants, service, clock);
    }

    private RefreshToken currentSession() {
        return stored(RefreshToken.issueForTenant(userId, tenantA, "h-raw-cur", now.minusSeconds(60), Duration.ofDays(7)));
    }

    @Test
    void doiTenantCapCapTokenMoiChoTenantDoVaThuHoiRefreshTokenHienTai() {
        RefreshToken current = currentSession();
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential(false, false)));
        when(tenants.switchTo(userId, tenantB)).thenReturn(new TenantChoice(tenantB, List.of(summaryA, summaryB)));

        TokenResponse response = switchTenant().execute(userId, tenantB, "raw-cur");

        assertThat(response.tenantId()).isEqualTo(tenantB);
        assertThat(issued.get(0).tenantId()).isEqualTo(tenantB);
        assertThat(current.isRevoked()).isTrue();
        assertThat(savedRefresh).contains(current);
        assertThat(savedRefresh).anySatisfy(t -> assertThat(t.tenantId()).isEqualTo(tenantB));
    }

    @Test
    void refreshTokenCuBiTuChoiSauKhiDoiTenant() {
        currentSession();
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential(false, false)));
        when(tenants.switchTo(userId, tenantB)).thenReturn(new TenantChoice(tenantB, List.of(summaryA, summaryB)));
        switchTenant().execute(userId, tenantB, "raw-cur");
        issued.clear();

        assertThatThrownBy(() -> refresh().execute("raw-cur")).isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(issued).isEmpty();
        assertThatThrownBy(() -> switchTenant().execute(userId, tenantB, "raw-cur"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void doiTenantVoiRefreshTokenCuaNguoiKhacBiTuChoiVaKhongThuHoi() {
        RefreshToken others = stored(RefreshToken.issueForTenant(UUID.randomUUID(), tenantA, "h-raw-other",
                now.minusSeconds(60), Duration.ofDays(7)));
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential(false, false)));
        when(tenants.switchTo(userId, tenantB)).thenReturn(new TenantChoice(tenantB, List.of(summaryA, summaryB)));

        assertThatThrownBy(() -> switchTenant().execute(userId, tenantB, "raw-other"))
                .isInstanceOf(InvalidRefreshTokenException.class);

        assertThat(others.isRevoked()).isFalse();
        assertThat(issued).isEmpty();
        assertThat(savedRefresh).isEmpty();
    }

    @Test
    void doiTenantVoiRefreshTokenKhongTonTaiHoacHetHanBiTuChoi() {
        stored(RefreshToken.issueForTenant(userId, tenantA, "h-raw-expired", now.minus(Duration.ofDays(8)), Duration.ofDays(7)));
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential(false, false)));
        when(tenants.switchTo(userId, tenantB)).thenReturn(new TenantChoice(tenantB, List.of(summaryA, summaryB)));

        assertThatThrownBy(() -> switchTenant().execute(userId, tenantB, "raw-unknown"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThatThrownBy(() -> switchTenant().execute(userId, tenantB, "raw-expired"))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(issued).isEmpty();
    }

    @Test
    void doiSangTenantKhongCoQuyenBiTuChoiVaRefreshTokenCuVanDungDuoc() {
        RefreshToken current = currentSession();
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential(false, false)));
        when(tenants.switchTo(userId, tenantB)).thenThrow(new NoTenantAccessException());
        when(tenants.verify(userId, tenantA)).thenReturn(new TenantChoice(tenantA, List.of(summaryA)));

        assertThatThrownBy(() -> switchTenant().execute(userId, tenantB, "raw-cur"))
                .isInstanceOf(NoTenantAccessException.class);

        assertThat(issued).isEmpty();
        assertThat(current.isRevoked()).isFalse();
        assertThat(savedRefresh).isEmpty();
        assertThat(refresh().execute("raw-cur").tenantId()).isEqualTo(tenantA);
    }

    // ---- tạo credential gắn membership ----

    @Test
    void taoCredentialThemMembershipVaoTenantCuaNguoiTao() {
        SaveCredentialPort save = mock(SaveCredentialPort.class);
        PasswordHasherPort hasher = mock(PasswordHasherPort.class);
        when(hasher.hash("password1")).thenReturn("bcrypt");
        when(save.save(any())).thenAnswer(inv -> inv.getArgument(0));
        RegisterCredentialUseCase register = new RegisterCredentialUseCase(credentials, save, hasher, tenants);

        UUID created = UUID.randomUUID();
        register.execute(created, "bob", "password1", null, false, tenantA);
        verify(tenants).addMember(created, tenantA);

        UUID noTenant = UUID.randomUUID();
        register.execute(noTenant, "carol", "password1", null, false, null);
        verify(tenants, never()).addMember(noTenant, null);
    }
}

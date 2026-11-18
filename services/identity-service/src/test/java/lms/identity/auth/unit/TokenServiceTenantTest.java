package lms.identity.auth.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.Set;
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
import lms.identity.auth.application.UserDirectoryPort;
import lms.identity.auth.application.PasswordHasherPort;
import lms.identity.auth.domain.DuplicateUsernameException;
import lms.identity.auth.domain.InvalidRefreshTokenException;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.RefreshTokenRepository;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import lms.identity.tenant.application.TenantChoice;
import lms.identity.tenant.application.TenantSummary;
import lms.identity.tenant.domain.NoTenantAccessException;
import lms.tenant.TenantContext;
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
    private final List<UUID> revokedAllFor = new ArrayList<>();
    private final List<String> revokedSessions = new ArrayList<>();
    private final TenantAccessPort tenants = mock(TenantAccessPort.class);
    private final RefreshTokenRepository refreshRepo = mock(RefreshTokenRepository.class);
    private final SaveRefreshTokenPort refreshSave = new SaveRefreshTokenPort() {
        public RefreshToken save(RefreshToken t) { savedRefresh.add(t); return t; }
        public void revokeAllByUserId(UUID u) { revokedAllFor.add(u); }
        public int revokeSession(UUID s, lms.identity.auth.domain.RevokeReason r, Instant n) {
            revokedSessions.add(s + ":" + r);
            return 1;
        }
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
        // port giả của test này không phân biệt tenant; phân biệt theo tenant xem các test "theo tenant" bên dưới
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

    // ---- quyền theo tenant đã chọn (role là dữ liệu thuộc tenant) ----

    /** Quyền của người dùng theo từng tenant; port đọc tenant từ TenantContext như adapter thật (role có @TenantId + RLS). */
    private final Map<UUID, UserAccessPort.UserAccess> accessByTenant = new HashMap<>();
    private final List<java.util.Optional<UUID>> tenantSeenByAccessPort = new ArrayList<>();

    private final TokenService perTenantService = new TokenService(
            id -> {
                java.util.Optional<UUID> current = TenantContext.current();
                tenantSeenByAccessPort.add(current);
                return current.map(accessByTenant::get)
                        .orElse(new UserAccessPort.UserAccess(id, List.of(), List.of(), false));
            },
            c -> { issued.add(c); return new TokenIssuerPort.IssuedToken("jwt", 900); },
            codec, refreshSave, clock, Duration.ofDays(7), id -> Optional.empty(), tenants);

    private void accessIn(UUID tenant, List<String> roles, List<String> permissions, boolean admin) {
        accessByTenant.put(tenant, new UserAccessPort.UserAccess(userId, roles, permissions, admin));
    }

    @Test
    void permissionsChiLayTuRoleCuaTenantDaChon() {
        accessIn(tenantA, List.of("LIBRARIAN"), List.of("BorrowingController/borrow"), false);
        accessIn(tenantB, List.of("AUDITOR"), List.of("AuditLogController/search"), false);
        when(tenants.selectForLogin(userId, "lib-b", false)).thenReturn(new TenantChoice(tenantB, List.of(summaryA, summaryB)));

        perTenantService.issueFor(credential(false, false), "lib-b");

        TokenIssuerPort.Claims claims = issued.get(0);
        assertThat(claims.tenantId()).isEqualTo(tenantB);
        assertThat(claims.roles()).containsExactly("AUDITOR");
        assertThat(claims.permissions()).containsExactly("AuditLogController/search");
        assertThat(tenantSeenByAccessPort).containsExactly(Optional.of(tenantB));
    }

    @Test
    void adminChiKhiCoRoleIsAdminOTenantDo() {
        accessIn(tenantA, List.of("SUPER_ADMIN"), List.of(), true);
        accessIn(tenantB, List.of("MEMBER"), List.of("TitleController/search"), false);
        when(tenants.selectForLogin(userId, null, false)).thenReturn(new TenantChoice(tenantA, List.of(summaryA, summaryB)));
        when(tenants.switchTo(userId, tenantB)).thenReturn(new TenantChoice(tenantB, List.of(summaryA, summaryB)));

        perTenantService.issueFor(credential(false, false));
        perTenantService.issueForTenant(credential(false, false), tenants.switchTo(userId, tenantB));

        assertThat(issued.get(0).admin()).isTrue();
        assertThat(issued.get(1).admin()).isFalse();
        assertThat(issued.get(1).roles()).containsExactly("MEMBER");
        assertThat(issued.get(1).permissions()).containsExactly("TitleController/search");
    }

    @Test
    void docQuyenTrongTenantDaChonRoiKhoiPhucTenantCuaRequest() {
        UUID requestTenant = UUID.randomUUID();
        accessIn(tenantB, List.of("MEMBER"), List.of("TitleController/search"), false);

        TenantContext.runAs(requestTenant, () ->
                perTenantService.issueForTenant(credential(false, false), new TenantChoice(tenantB, List.of(summaryB))));

        assertThat(tenantSeenByAccessPort).containsExactly(Optional.of(tenantB));
        assertThat(issued.get(0).permissions()).containsExactly("TitleController/search");
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void phienNenTangKhongCoRoleTenantNaoVaKhongDocQuyen() {
        when(tenants.selectForLogin(userId, null, true)).thenReturn(new TenantChoice(null, List.of()));

        perTenantService.issueFor(credential(false, true));

        TokenIssuerPort.Claims claims = issued.get(0);
        assertThat(claims.platformAdmin()).isTrue();
        assertThat(claims.roles()).isEmpty();
        assertThat(claims.permissions()).isEmpty();
        assertThat(claims.admin()).isFalse();
        assertThat(tenantSeenByAccessPort).isEmpty();
    }

    @Test
    void phongBanTrongTokenLaPhongBanCuaMembershipOTenantDaChon() {
        UUID deptInB = UUID.randomUUID();
        when(tenants.selectForLogin(userId, null, false))
                .thenReturn(new TenantChoice(tenantB, List.of(summaryA, summaryB), deptInB));

        perTenantService.issueFor(credential(false, false));

        assertThat(issued.get(0).departmentId()).isEqualTo(deptInB);
    }

    // ---- claim modules (ADR-0009) ----

    @Test
    void dangNhapPhatClaimModulesCuaTenantDaChon() {
        TenantSummary withModules = new TenantSummary(tenantA, "lib-a", "Thư viện A", List.of("LENDING", "SALES"));
        when(tenants.selectForLogin(userId, "lib-a", false)).thenReturn(new TenantChoice(tenantA, List.of(withModules)));

        service.issueFor(credential(false, false), "lib-a");

        assertThat(issued.get(0).modules()).containsExactly("LENDING", "SALES");
        // ADR-0022: module lấy từ choice.available(), không đọc lại
        verify(tenants, never()).modules(any());
    }

    @Test
    void doiTenantPhatModulesCuaTenantDich() {
        TenantSummary a = new TenantSummary(tenantA, "lib-a", "Thư viện A", List.of("LENDING"));
        TenantSummary b = new TenantSummary(tenantB, "lib-b", "Thư viện B", List.of("SALES"));

        service.issueForTenant(credential(false, false), new TenantChoice(tenantA, List.of(a, b)));
        service.issueForTenant(credential(false, false), new TenantChoice(tenantB, List.of(a, b)));

        assertThat(issued.get(0).modules()).containsExactly("LENDING");
        assertThat(issued.get(1).modules()).containsExactly("SALES");
    }

    @Test
    void phienNenTangKhongCoTenantThiKhongCoClaimModulesVaKhongDocModules() {
        when(tenants.selectForLogin(userId, null, true)).thenReturn(new TenantChoice(null, List.of()));

        service.issueFor(credential(false, true));

        assertThat(issued.get(0).modules()).isNull();
        verify(tenants, never()).modules(any());
    }

    @Test
    void tokenPhaiDoiMatKhauVanMangModules() {
        TenantSummary withModules = new TenantSummary(tenantA, "lib-a", "Thư viện A", List.of("LENDING"));
        when(tenants.selectForLogin(userId, null, false)).thenReturn(new TenantChoice(tenantA, List.of(withModules)));

        service.issueFor(credential(true, false));

        assertThat(issued.get(0).mustChangePassword()).isTrue();
        assertThat(issued.get(0).modules()).containsExactly("LENDING");
    }

    @Test
    void refreshDocLaiModulesMoiNhatCuaTenant() {
        RefreshToken old = stored(RefreshToken.issueForTenant(userId, tenantB, "h-raw-old", now.minusSeconds(60),
                Duration.ofDays(7)));
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential(false, false)));
        TenantSummary withModules = new TenantSummary(tenantB, "lib-b", "Thư viện B", List.of("LENDING", "SALES"));
        when(tenants.verify(userId, tenantB)).thenReturn(new TenantChoice(tenantB, List.of(withModules)));

        refresh().execute("raw-old");

        assertThat(old.isRevoked()).isTrue();
        assertThat(issued.get(0).modules()).containsExactly("LENDING", "SALES");
    }

    // ---- refresh ----

    private RefreshTokenUseCase refresh() {
        return new RefreshTokenUseCase(refreshRepo, refreshSave, credentials, codec, service, tenants, clock);
    }

    private RefreshTokenUseCase refreshWithGrace(Duration grace) {
        return new RefreshTokenUseCase(refreshRepo, refreshSave, credentials, codec, service, tenants, clock, grace);
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
    void loiTruocBuocLuuThiRefreshTokenHienTaiKhongBiThuHoi() {
        // R20: thu hồi token cũ chỉ xảy ra cùng giao dịch lưu token mới, ở bước cuối; lỗi đọc quyền trước đó thì giữ nguyên
        TokenService failing = new TokenService(id -> { throw new IllegalStateException("DB lỗi"); },
                c -> { issued.add(c); return new TokenIssuerPort.IssuedToken("jwt", 900); },
                codec, refreshSave, clock, Duration.ofDays(7), id -> Optional.empty(), tenants);
        RefreshToken old = stored(RefreshToken.issueForTenant(userId, tenantB, "h-raw-old", now.minusSeconds(60), Duration.ofDays(7)));
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential(false, false)));
        when(tenants.verify(userId, tenantB)).thenReturn(new TenantChoice(tenantB, List.of(summaryB)));

        assertThatThrownBy(() -> new RefreshTokenUseCase(refreshRepo, refreshSave, credentials, codec, failing, tenants,
                clock).execute("raw-old")).isInstanceOf(IllegalStateException.class);

        assertThat(old.isRevoked()).isFalse();
        assertThat(savedRefresh).isEmpty();
        assertThat(issued).isEmpty();
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

        // Vừa xoay (trong ân hạn): từ chối mềm, không phát token, không thu hồi gì
        assertThatThrownBy(() -> refresh().execute("raw-cur"))
                .isInstanceOf(lms.identity.auth.domain.RefreshTokenSupersededException.class);
        assertThat(issued).isEmpty();
        assertThat(revokedAllFor).isEmpty();
        assertThat(revokedSessions).isEmpty();
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
        RegisterCredentialUseCase register = new RegisterCredentialUseCase(credentials, save, hasher, tenants,
                id -> Optional.empty());

        UUID created = UUID.randomUUID();
        register.execute(created, "bob", "password1", null, false, tenantA);
        verify(tenants).addMember(created, tenantA, null);

        // m1: phòng ban của tài khoản mới ghi vào membership của tenant (token đọc phòng ban từ đây)
        UUID withDept = UUID.randomUUID();
        UUID dept = UUID.randomUUID();
        register.execute(withDept, "dave", "password1", dept, false, tenantA);
        verify(tenants).addMember(withDept, tenantA, dept);

        UUID noTenant = UUID.randomUUID();
        register.execute(noTenant, "carol", "password1", null, false, null);
        verify(tenants, never()).addMember(eq(noTenant), any(), any());
    }

    // ---- F-3: không cấp credential cho userId thuộc tenant khác ----

    private RegisterCredentialUseCase registerWith(Set<UUID> usersRows) {
        SaveCredentialPort save = mock(SaveCredentialPort.class);
        PasswordHasherPort hasher = mock(PasswordHasherPort.class);
        when(hasher.hash("password1")).thenReturn("bcrypt");
        when(save.save(any())).thenAnswer(inv -> inv.getArgument(0));
        return new RegisterCredentialUseCase(credentials, save, hasher, tenants,
                id -> usersRows.contains(id) ? Optional.of(new UserDirectoryPort.UserInfo(false)) : Optional.empty());
    }

    @Test
    void tuChoiTaoCredentialChoUserIdChiCoMembershipOTenantKhac() {
        UUID victim = UUID.randomUUID();
        when(tenants.hasAnyMembership(victim)).thenReturn(true);
        when(tenants.isMember(victim, tenantA)).thenReturn(false);

        assertThatThrownBy(() -> registerWith(Set.of()).execute(victim, "mallory", "password1", null, false, tenantA))
                .isInstanceOf(DuplicateUsernameException.class)
                .hasMessageNotContaining("mallory");
        verify(tenants, never()).addMember(any(), any(), any());
    }

    @Test
    void tuChoiTaoCredentialChoUserIdCoHoSoUsersNhungKhongThuocTenantNguoiGoi() {
        UUID victim = UUID.randomUUID();

        assertThatThrownBy(() -> registerWith(Set.of(victim)).execute(victim, "mallory", "password1", null, false,
                tenantA)).isInstanceOf(DuplicateUsernameException.class);
        verify(tenants, never()).addMember(any(), any(), any());
    }

    @Test
    void choPhepTaoCredentialChoUserIdDaLaThanhVienTenantNguoiGoi() {
        UUID member = UUID.randomUUID();
        when(tenants.hasAnyMembership(member)).thenReturn(true);
        when(tenants.isMember(member, tenantA)).thenReturn(true);

        registerWith(Set.of(member)).execute(member, "bob", "password1", null, false, tenantA);

        verify(tenants).addMember(member, tenantA, null);
    }
    // ---- refresh có tenantId (ADR-0013 2.4) ----

    @Test
    void refreshCoTenantIdDungThiXoayVongBinhThuong() {
        RefreshToken old = stored(RefreshToken.issueForTenant(userId, tenantB, "h-raw-old", now.minusSeconds(60), Duration.ofDays(7)));
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential(false, false)));
        when(tenants.verify(userId, tenantB)).thenReturn(new TenantChoice(tenantB, List.of(summaryB)));

        TokenResponse response = refresh().execute("raw-old", lms.identity.auth.domain.TokenChannel.STAFF, tenantB);

        assertThat(old.isRevoked()).isTrue();
        assertThat(response.tenantId()).isEqualTo(tenantB);
    }

    @Test
    void refreshSaiTenantIdLa401KhongXoayVongVaTokenVanDungDuocODungTenant() {
        RefreshToken old = stored(RefreshToken.issueForTenant(userId, tenantB, "h-raw-old", now.minusSeconds(60), Duration.ofDays(7)));
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential(false, false)));
        when(tenants.verify(userId, tenantB)).thenReturn(new TenantChoice(tenantB, List.of(summaryB)));

        assertThatThrownBy(() -> refresh().execute("raw-old", lms.identity.auth.domain.TokenChannel.STAFF, tenantA))
                .isInstanceOf(InvalidRefreshTokenException.class);

        assertThat(old.isRevoked()).isFalse();
        assertThat(savedRefresh).isEmpty();
        assertThat(issued).isEmpty();
        assertThat(revokedAllFor).isEmpty();
        // token không bị đốt: dùng đúng tenant vẫn được
        assertThat(refresh().execute("raw-old", lms.identity.auth.domain.TokenChannel.STAFF, tenantB).tenantId())
                .isEqualTo(tenantB);
    }

    @Test
    void phienNenTangCoTenantIdLa401VaKhongXoayVong() {
        RefreshToken old = stored(RefreshToken.issueForPlatform(userId, "h-raw-old", now.minusSeconds(60), Duration.ofDays(7)));

        assertThatThrownBy(() -> refresh().execute("raw-old", lms.identity.auth.domain.TokenChannel.STAFF, tenantA))
                .isInstanceOf(InvalidRefreshTokenException.class);

        assertThat(old.isRevoked()).isFalse();
        assertThat(savedRefresh).isEmpty();
    }

    @Test
    void khongGuiTenantIdThiNhuCu() {
        RefreshToken old = stored(RefreshToken.issueForTenant(userId, tenantB, "h-raw-old", now.minusSeconds(60), Duration.ofDays(7)));
        when(credentials.findByUserId(userId)).thenReturn(Optional.of(credential(false, false)));
        when(tenants.verify(userId, tenantB)).thenReturn(new TenantChoice(tenantB, List.of(summaryB)));

        refresh().execute("raw-old", lms.identity.auth.domain.TokenChannel.STAFF, null);

        assertThat(old.isRevoked()).isTrue();
    }

    @Test
    void tokenDaThuHoiGuiQuaTenantSaiVanKichHoatThuHoiHoPhien() {
        RefreshToken old = stored(RefreshToken.issueForTenant(userId, tenantB, "h-raw-old", now.minusSeconds(60), Duration.ofDays(7)));
        old.revoke();

        assertThatThrownBy(() -> refresh().execute("raw-old", lms.identity.auth.domain.TokenChannel.STAFF, tenantA))
                .isInstanceOf(InvalidRefreshTokenException.class);

        // Chỉ thu hồi họ phiên của token (không revokeAllByUserId)
        assertThat(revokedSessions).containsExactly(old.sessionId() + ":REUSE_DETECTED");
        assertThat(revokedAllFor).isEmpty();
    }
}

package lms.identity.auth.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.identity.auth.application.ClientInfo;
import lms.identity.auth.application.ListMySessionsUseCase;
import lms.identity.auth.application.ListTenantUserSessionsUseCase;
import lms.identity.auth.application.LogoutUseCase;
import lms.identity.auth.application.PublishAuthEventPort;
import lms.identity.auth.application.PurgeExpiredRefreshTokensUseCase;
import lms.identity.auth.application.RefreshTokenCodecPort;
import lms.identity.auth.application.RefreshTokenUseCase;
import lms.identity.auth.application.RevokeMySessionUseCase;
import lms.identity.auth.application.RevokeMySessionsUseCase;
import lms.identity.auth.application.RevokeTenantSessionsUseCase;
import lms.identity.auth.application.RevokeTenantUserSessionUseCase;
import lms.identity.auth.application.SaveRefreshTokenPort;
import lms.identity.auth.application.SessionView;
import lms.identity.auth.application.TenantAccessPort;
import lms.identity.auth.application.TenantSessionRevokerPort;
import lms.identity.auth.application.TenantUserSession;
import lms.identity.auth.application.TokenIssuerPort;
import lms.identity.auth.application.TokenService;
import lms.identity.auth.application.UserAccessPort;
import lms.identity.auth.domain.DeviceSummary;
import lms.identity.auth.domain.InvalidRefreshTokenException;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.RefreshTokenRepository;
import lms.identity.auth.domain.RefreshTokenSupersededException;
import lms.identity.auth.domain.RevokeReason;
import lms.identity.auth.domain.SessionNotFoundException;
import lms.identity.auth.domain.SessionNotIdentifiedException;
import lms.identity.auth.domain.TokenChannel;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import lms.identity.auth.domain.UserSessionsRevokedEvent;
import lms.identity.auth.domain.UserSessionsViewedEvent;
import lms.identity.tenant.application.TenantChoice;
import lms.identity.tenant.application.TenantSummary;
import org.junit.jupiter.api.Test;

/** Họ phiên, ân hạn refresh, thu hồi đúng họ, API phiên (ADR-0017). Thuần đơn vị, không Spring. */
class SessionFamilyTest {

    private final Instant now = Instant.parse("2026-11-17T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final UUID userId = UUID.randomUUID();
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private static final String UA_CHROME_WIN =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36";
    private static final String UA_SAFARI_IOS =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 "
                    + "Mobile/15E148 Safari/604.1";

    private final List<TokenIssuerPort.Claims> claims = new ArrayList<>();
    private final List<RefreshToken> saved = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();
    private boolean rotateConflict;
    private int seq;

    private final SaveRefreshTokenPort port = new SaveRefreshTokenPort() {
        public RefreshToken save(RefreshToken t) { saved.add(t); return t; }
        public void revokeAllByUserId(UUID u) { calls.add("revokeAll:" + u); }
        public void rotate(RefreshToken old, RefreshToken issued) {
            if (rotateConflict) throw new RefreshTokenSupersededException();
            if (old != null) saved.add(old);
            saved.add(issued);
        }
        public int revokeSession(UUID s, RevokeReason r, Instant n) { calls.add("revokeSession:" + s + ":" + r); return 1; }
    };
    private final RefreshTokenCodecPort codec = new RefreshTokenCodecPort() {
        public String generate() { return "raw-" + (++seq); }
        public String hash(String raw) { return "h-" + raw; }
    };
    private final TenantAccessPort tenants = mock(TenantAccessPort.class);
    private final TokenService service = new TokenService(
            id -> new UserAccessPort.UserAccess(id, List.of("R"), List.of("k"), false),
            c -> { claims.add(c); return new TokenIssuerPort.IssuedToken("jwt", 900); },
            codec, port, clock, Duration.ofDays(7), id -> Optional.empty(), tenants);
    private final UserCredential credential =
            UserCredential.restore(userId, "alice", "h", null, true, 0, null, false, false);

    private TenantChoice choiceA() {
        return new TenantChoice(tenantA, List.of(new TenantSummary(tenantA, "a", "A")));
    }

    private RefreshToken lastSaved() {
        return saved.get(saved.size() - 1);
    }

    // ---- TokenService: sessionId ----

    @Test
    void moiLanDangNhapTaoSessionIdMoiVaTokenMangSid() {
        when(tenants.selectForLogin(userId, null, false)).thenReturn(choiceA());

        service.issueFor(credential, null, TokenChannel.STAFF, ClientInfo.NONE);
        RefreshToken first = lastSaved();
        service.issueFor(credential, null, TokenChannel.STAFF, ClientInfo.NONE);
        RefreshToken second = lastSaved();

        assertThat(first.sessionId()).isNotEqualTo(second.sessionId());
        assertThat(claims.get(0).sessionId()).isEqualTo(first.sessionId());
        assertThat(claims.get(1).sessionId()).isEqualTo(second.sessionId());
    }

    @Test
    void kenhStorefrontCungMangSid() {
        when(tenants.selectForLogin(userId, "shop", false)).thenReturn(choiceA());
        service.issueFor(credential, "shop", TokenChannel.STOREFRONT, ClientInfo.NONE);
        assertThat(claims.get(0).sessionId()).isEqualTo(lastSaved().sessionId());
        assertThat(claims.get(0).audience()).isEqualTo("lms-storefront");
    }

    @Test
    void refreshVaDoiTenantGiuSessionIdVaThoiDiemBatDauPhien() {
        when(tenants.selectForLogin(userId, null, false)).thenReturn(choiceA());
        service.issueFor(credential, null, TokenChannel.STAFF, ClientInfo.of("1.2.3.4", UA_CHROME_WIN));
        RefreshToken t1 = lastSaved();

        service.issueForTenant(credential, choiceA(), t1, null);
        RefreshToken t2 = lastSaved();
        service.issueForTenant(credential, new TenantChoice(tenantB, List.of()), t2, null);
        RefreshToken t3 = lastSaved();

        assertThat(t2.sessionId()).isEqualTo(t1.sessionId());
        assertThat(t3.sessionId()).isEqualTo(t1.sessionId());
        assertThat(t3.sessionStartedAt()).isEqualTo(t1.sessionStartedAt());
        assertThat(t3.tenantId()).isEqualTo(tenantB);
        assertThat(claims.get(2).sessionId()).isEqualTo(t1.sessionId());
        assertThat(t1.isRevoked()).isTrue();
        assertThat(t1.revokedReason()).isEqualTo(RevokeReason.ROTATED);
        assertThat(t1.revokedAt()).isEqualTo(now);
    }

    @Test
    void clientInfoNullChepNhanThietBiTuTokenCuVaTokenCuBiXoaNhan() {
        when(tenants.selectForLogin(userId, null, false)).thenReturn(choiceA());
        service.issueFor(credential, null, TokenChannel.STAFF, ClientInfo.of("1.2.3.4", UA_CHROME_WIN));
        RefreshToken t1 = lastSaved();
        assertThat(t1.deviceSummary()).isEqualTo("Chrome trên Windows");
        assertThat(t1.clientIp()).isEqualTo("1.2.3.4");

        service.issueForTenant(credential, choiceA(), t1, null);
        RefreshToken t2 = lastSaved();

        assertThat(t2.deviceSummary()).isEqualTo("Chrome trên Windows");
        assertThat(t2.clientIp()).isEqualTo("1.2.3.4");
        // Dòng cũ đã xoay: không giữ lịch sử IP/thiết bị
        assertThat(t1.deviceSummary()).isNull();
        assertThat(t1.clientIp()).isNull();
    }

    @Test
    void clientInfoMoiGhiDeNhanThietBiVaIp() {
        when(tenants.selectForLogin(userId, null, false)).thenReturn(choiceA());
        service.issueFor(credential, null, TokenChannel.STAFF, ClientInfo.of("1.2.3.4", UA_CHROME_WIN));
        service.issueForTenant(credential, choiceA(), lastSaved(), ClientInfo.of("5.6.7.8", UA_SAFARI_IOS));
        assertThat(lastSaved().deviceSummary()).isEqualTo("Safari trên iOS");
        assertThat(lastSaved().clientIp()).isEqualTo("5.6.7.8");
    }

    @Test
    void xoayVongBiTranhGiuaChungThiNemSupersededVaKhongPhatTokenRa() {
        when(tenants.selectForLogin(userId, null, false)).thenReturn(choiceA());
        service.issueFor(credential, null, TokenChannel.STAFF, ClientInfo.NONE);
        RefreshToken t1 = lastSaved();
        int before = saved.size();
        rotateConflict = true;

        assertThatThrownBy(() -> service.issueForTenant(credential, choiceA(), t1, null))
                .isInstanceOf(RefreshTokenSupersededException.class);
        assertThat(saved).hasSize(before); // không chèn token mới
    }

    // ---- RefreshTokenUseCase: ân hạn và thu hồi đúng họ ----

    private final RefreshTokenRepository repo = mock(RefreshTokenRepository.class);
    private final UserCredentialRepository credentials = mock(UserCredentialRepository.class);

    private RefreshTokenUseCase refresh(Duration grace) {
        return new RefreshTokenUseCase(repo, port, credentials, codec, service, tenants, clock, grace);
    }

    private RefreshToken revokedToken(RevokeReason reason, Instant revokedAt) {
        RefreshToken t = RefreshToken.restore(UUID.randomUUID(), userId, tenantA, false, "h-old", now.plusSeconds(3600),
                true, now.minusSeconds(900), TokenChannel.STAFF, UUID.randomUUID(), now.minusSeconds(900), null, null,
                revokedAt, reason);
        when(repo.findByTokenHash("h-old")).thenReturn(Optional.of(t));
        return t;
    }

    @Test
    void tokenVuaXoayTrongAnHanBiTuChoiMemKhongThuHoiGiVaKhongPhatToken() {
        revokedToken(RevokeReason.ROTATED, now.minusSeconds(29));

        assertThatThrownBy(() -> refresh(Duration.ofSeconds(30)).execute("old"))
                .isInstanceOf(RefreshTokenSupersededException.class);

        assertThat(calls).isEmpty();
        assertThat(claims).isEmpty();
        assertThat(saved).isEmpty();
    }

    @Test
    void tokenXoayNgoaiAnHanChiThuHoiDungHoPhienKhongRevokeAll() {
        RefreshToken t = revokedToken(RevokeReason.ROTATED, now.minusSeconds(31));

        assertThatThrownBy(() -> refresh(Duration.ofSeconds(30)).execute("old"))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .isNotInstanceOf(RefreshTokenSupersededException.class);

        assertThat(calls).containsExactly("revokeSession:" + t.sessionId() + ":REUSE_DETECTED");
    }

    @Test
    void anHanBangKhongLuonThuHoiHo() {
        RefreshToken t = revokedToken(RevokeReason.ROTATED, now.minusSeconds(1));
        assertThatThrownBy(() -> refresh(Duration.ZERO).execute("old")).isInstanceOf(InvalidRefreshTokenException.class)
                .isNotInstanceOf(RefreshTokenSupersededException.class);
        assertThat(calls).containsExactly("revokeSession:" + t.sessionId() + ":REUSE_DETECTED");
    }

    @Test
    void tokenLogoutHoacBulkDungLaiTrongCuaSoVanThuHoiHo() {
        for (RevokeReason reason : List.of(RevokeReason.LOGOUT, RevokeReason.BULK, RevokeReason.SESSION_REVOKED)) {
            calls.clear();
            RefreshToken t = revokedToken(reason, now.minusSeconds(5));
            assertThatThrownBy(() -> refresh(Duration.ofSeconds(30)).execute("old"))
                    .isInstanceOf(InvalidRefreshTokenException.class)
                    .isNotInstanceOf(RefreshTokenSupersededException.class);
            assertThat(calls).containsExactly("revokeSession:" + t.sessionId() + ":REUSE_DETECTED");
        }
    }

    @Test
    void tokenDungNhungSaiTenantKhongBiThuHoiThuTuKiemAdr0013KhongDoi() {
        RefreshToken live = RefreshToken.issueForTenant(userId, tenantA, "h-live", now.minusSeconds(60),
                Duration.ofDays(7));
        when(repo.findByTokenHash("h-live")).thenReturn(Optional.of(live));

        assertThatThrownBy(() -> refresh(Duration.ofSeconds(30)).execute("live", TokenChannel.STAFF, tenantB))
                .isInstanceOf(InvalidRefreshTokenException.class);

        assertThat(live.isRevoked()).isFalse();
        assertThat(calls).isEmpty();
    }

    // ---- Logout ----

    @Test
    void logoutThuHoiCaHoVoiLyDoLogoutVaIdempotent() {
        RefreshToken live = RefreshToken.issueForTenant(userId, tenantA, "h-live", now.minusSeconds(60),
                Duration.ofDays(7));
        when(repo.findByTokenHash("h-live")).thenReturn(Optional.of(live));
        LogoutUseCase logout = new LogoutUseCase(repo, port, codec, clock);

        logout.execute("live");
        assertThat(calls).containsExactly("revokeSession:" + live.sessionId() + ":LOGOUT");

        calls.clear();
        RefreshToken dead = revokedToken(RevokeReason.ROTATED, now.minusSeconds(5));
        logout.execute("old");
        logout.execute("khong-ton-tai");
        assertThat(calls).isEmpty();
        assertThat(dead.isRevoked()).isTrue();
    }

    // ---- DeviceSummary, ClientInfo ----

    @Test
    void deviceSummaryNhanDienTrinhDuyetVaHeDieuHanh() {
        assertThat(DeviceSummary.of(UA_CHROME_WIN)).isEqualTo("Chrome trên Windows");
        assertThat(DeviceSummary.of(UA_SAFARI_IOS)).isEqualTo("Safari trên iOS");
        assertThat(DeviceSummary.of("Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0"))
                .isEqualTo("Firefox trên Linux");
        assertThat(DeviceSummary.of("Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/120.0 Safari/537.36 Edg/120.0"))
                .isEqualTo("Edge trên Windows");
        assertThat(DeviceSummary.of("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"))
                .isEqualTo("Chrome trên Android");
        assertThat(DeviceSummary.of("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Version/17.0 Safari/605.1.15"))
                .isEqualTo("Safari trên macOS");
    }

    @Test
    void deviceSummaryXuLyChuoiRongRacVaQuaDai() {
        assertThat(DeviceSummary.of(null)).isEqualTo("Không rõ");
        assertThat(DeviceSummary.of("")).isEqualTo("Không rõ");
        assertThat(DeviceSummary.of("   ")).isEqualTo("Không rõ");
        assertThat(DeviceSummary.of("curl/8.4.0")).isEqualTo("Không rõ");
        assertThat(DeviceSummary.of("x".repeat(10_000))).isEqualTo("Không rõ");
        assertThat(DeviceSummary.of("Chrome/1\u0000\u0007\n Windows" + "y".repeat(10_000)).length())
                .isLessThanOrEqualTo(DeviceSummary.MAX_LENGTH);
        assertThat(DeviceSummary.of("Chrome/1\u0000\u0007\n Windows")).doesNotContain("\u0000", "\u0007", "\n");
    }

    @Test
    void clientInfoChiNhanIpDungDinhDang() {
        assertThat(ClientInfo.of("113.161.45.9", null).ip()).isEqualTo("113.161.45.9");
        assertThat(ClientInfo.of("2001:db8::1", null).ip()).isEqualTo("2001:db8::1");
        assertThat(ClientInfo.of("::ffff:1.2.3.4", null).ip()).isEqualTo("::ffff:1.2.3.4");
        assertThat(ClientInfo.of("999.1.1.1", null).ip()).isNull();
        assertThat(ClientInfo.of("1.2.3", null).ip()).isNull();
        assertThat(ClientInfo.of("evil<script>", null).ip()).isNull();
        assertThat(ClientInfo.of("1.2.3.4, 5.6.7.8", null).ip()).isNull();
        assertThat(ClientInfo.of("a".repeat(60), null).ip()).isNull();
        assertThat(ClientInfo.of(null, "  ").userAgent()).isNull();
        assertThat(ClientInfo.of(null, "u".repeat(2000)).userAgent()).hasSize(512);
    }

    // ---- Phiên của tôi ----

    @Test
    void danhSachPhienGomMoiTenantMoiKenhDanhDauCurrentVaSapTheoDungGanNhat() {
        UUID sidOld = UUID.randomUUID();
        UUID sidNew = UUID.randomUUID();
        RefreshToken old = RefreshToken.restore(UUID.randomUUID(), userId, tenantA, false, "h1", now.plusSeconds(100),
                false, now.minusSeconds(500), TokenChannel.STAFF, sidOld, now.minusSeconds(900), "Chrome trên Windows",
                "1.1.1.1", null, null);
        RefreshToken fresh = RefreshToken.restore(UUID.randomUUID(), userId, null, true, "h2", now.plusSeconds(100),
                false, now.minusSeconds(5), TokenChannel.STAFF, sidNew, now.minusSeconds(50), null, null, null, null);
        RefreshToken shop = RefreshToken.restore(UUID.randomUUID(), userId, tenantB, false, "h3", now.plusSeconds(100),
                false, now.minusSeconds(100), TokenChannel.STOREFRONT, UUID.randomUUID(), now.minusSeconds(100), null,
                null, null, null);
        RefreshTokenRepository r = mock(RefreshTokenRepository.class);
        when(r.findActiveByUserId(userId, now)).thenReturn(List.of(old, fresh, shop));
        when(tenants.available(userId)).thenReturn(List.of(new TenantSummary(tenantA, "a", "Thư viện A")));

        List<SessionView> views = new ListMySessionsUseCase(r, tenants, clock).execute(userId, sidOld);

        assertThat(views).extracting(SessionView::sessionId).first().isEqualTo(sidNew);
        assertThat(views).hasSize(3);
        SessionView mine = views.stream().filter(v -> v.sessionId().equals(sidOld)).findFirst().orElseThrow();
        assertThat(mine.current()).isTrue();
        assertThat(mine.tenantName()).isEqualTo("Thư viện A");
        assertThat(mine.ipAddress()).isEqualTo("1.1.1.1");
        assertThat(views.stream().filter(SessionView::current)).hasSize(1);
        assertThat(views).anySatisfy(v -> {
            assertThat(v.tenantId()).isNull(); // phiên nền tảng
            assertThat(v.channel()).isEqualTo("STAFF");
        });
        assertThat(views).anySatisfy(v -> assertThat(v.channel()).isEqualTo("STOREFRONT"));
    }

    @Test
    void khongXoaDuocPhienNguoiKhacTra404Chung() {
        SaveRefreshTokenPort p = mock(SaveRefreshTokenPort.class);
        UUID other = UUID.randomUUID();
        when(p.revokeSessionOfUser(userId, other, now)).thenReturn(0);
        assertThatThrownBy(() -> new RevokeMySessionUseCase(p, clock).execute(userId, other))
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    void xoaPhienHienTaiDuocPhep() {
        SaveRefreshTokenPort p = mock(SaveRefreshTokenPort.class);
        UUID sid = UUID.randomUUID();
        when(p.revokeSessionOfUser(userId, sid, now)).thenReturn(1);
        new RevokeMySessionUseCase(p, clock).execute(userId, sid);
        verify(p).revokeSessionOfUser(userId, sid, now);
    }

    @Test
    void keepCurrentThieuSidTra409VaKhongThuHoiGi() {
        SaveRefreshTokenPort p = mock(SaveRefreshTokenPort.class);
        assertThatThrownBy(() -> new RevokeMySessionsUseCase(p, clock).execute(userId, null, true))
                .isInstanceOf(SessionNotIdentifiedException.class);
        verifyNoInteractions(p);
    }

    @Test
    void keepCurrentGiuPhienHienTaiVaFalseThuHoiTatCa() {
        SaveRefreshTokenPort p = mock(SaveRefreshTokenPort.class);
        UUID sid = UUID.randomUUID();
        when(p.revokeAllExcept(userId, sid, now)).thenReturn(2);
        when(p.revokeAllExcept(userId, null, now)).thenReturn(3);
        RevokeMySessionsUseCase uc = new RevokeMySessionsUseCase(p, clock);
        assertThat(uc.execute(userId, sid, true)).isEqualTo(2);
        assertThat(uc.execute(userId, sid, false)).isEqualTo(3);
        assertThat(uc.execute(userId, null, false)).isEqualTo(3); // không sid vẫn đăng xuất tất cả được
    }

    // ---- Dọn dẹp ----

    @Test
    void jobDonXoaTheoLoDenKhiHetVaDungMocHetHanQuaMotNgay() {
        SaveRefreshTokenPort p = mock(SaveRefreshTokenPort.class);
        Instant cutoff = now.minus(Duration.ofDays(1));
        when(p.deleteExpiredBefore(cutoff, 1000)).thenReturn(1000, 1000, 250);
        int total = new PurgeExpiredRefreshTokensUseCase(p, clock, Duration.ofDays(1), 1000).execute();
        assertThat(total).isEqualTo(2250);
        verify(p, org.mockito.Mockito.times(3)).deleteExpiredBefore(cutoff, 1000);
    }

    // ---- Admin tenant: phiên của người khác ----

    private final PublishAuthEventPort events = mock(PublishAuthEventPort.class);

    @Test
    void xemPhienNguoiKhacPhatSuKienAuditKhongChuaIpTuXemThiKhong() {
        UUID target = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        RefreshToken t = RefreshToken.restore(UUID.randomUUID(), target, tenantA, false, "h", now.plusSeconds(60), false,
                now, TokenChannel.STAFF, UUID.randomUUID(), now, "Chrome trên Windows", "9.9.9.9", null, null);
        RefreshTokenRepository r = mock(RefreshTokenRepository.class);
        when(r.findActiveByUserIdAndTenantId(target, tenantA, now)).thenReturn(List.of(t));
        ListTenantUserSessionsUseCase uc = new ListTenantUserSessionsUseCase(r, events, clock);

        List<TenantUserSession> result = uc.execute(target, tenantA, admin);
        assertThat(result).singleElement().satisfies(s -> assertThat(s.ipAddress()).isEqualTo("9.9.9.9"));
        org.mockito.ArgumentCaptor<lms.common.kernel.DomainEvent> c =
                org.mockito.ArgumentCaptor.forClass(lms.common.kernel.DomainEvent.class);
        verify(events).publish(c.capture());
        UserSessionsViewedEvent e = (UserSessionsViewedEvent) c.getValue();
        assertThat(e.actorId()).isEqualTo(admin);
        assertThat(e.targetUserId()).isEqualTo(target);
        assertThat(e.tenantId()).isEqualTo(tenantA);
        assertThat(e.sessionCount()).isEqualTo(1);
        assertThat(e.occurredAt()).isEqualTo(now);
        assertThat(e.toString()).doesNotContain("9.9.9.9").doesNotContain("Chrome");

        org.mockito.Mockito.clearInvocations(events);
        uc.execute(target, tenantA, null); // tự xem: không audit
        verifyNoInteractions(events);
    }

    @Test
    void thuHoiMotPhienKhongKhopTra404KhongSuKienKhopThiPhatDungMotSuKien() {
        TenantSessionRevokerPort revoker = mock(TenantSessionRevokerPort.class);
        UUID sid = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        RevokeTenantUserSessionUseCase uc = new RevokeTenantUserSessionUseCase(revoker, events, clock);

        when(revoker.revokeSessionInTenant(userId, tenantA, sid)).thenReturn(0);
        assertThatThrownBy(() -> uc.execute(userId, tenantA, sid, admin)).isInstanceOf(SessionNotFoundException.class);
        assertThatThrownBy(() -> uc.execute(userId, null, sid, admin)).isInstanceOf(SessionNotFoundException.class);
        verifyNoInteractions(events);

        when(revoker.revokeSessionInTenant(userId, tenantA, sid)).thenReturn(1);
        uc.execute(userId, tenantA, sid, admin);
        org.mockito.ArgumentCaptor<lms.common.kernel.DomainEvent> c =
                org.mockito.ArgumentCaptor.forClass(lms.common.kernel.DomainEvent.class);
        verify(events).publish(c.capture());
        UserSessionsRevokedEvent e = (UserSessionsRevokedEvent) c.getValue();
        assertThat(e.targetUserId()).isEqualTo(userId);
        assertThat(e.revokedBy()).isEqualTo(admin);
        assertThat(e.sessionId()).isEqualTo(sid);
        assertThat(e.revokedCount()).isEqualTo(1);
    }

    @Test
    void thuHoiTatCaTrongTenantPhatSuKienCoSoLuongVaSessionIdNull() {
        TenantSessionRevokerPort revoker = mock(TenantSessionRevokerPort.class);
        UUID admin = UUID.randomUUID();
        when(revoker.revokeAllInTenant(userId, tenantA)).thenReturn(4);
        RevokeTenantSessionsUseCase uc = new RevokeTenantSessionsUseCase(revoker, events, clock);

        assertThat(uc.execute(userId, tenantA, admin)).isEqualTo(4);
        org.mockito.ArgumentCaptor<lms.common.kernel.DomainEvent> c =
                org.mockito.ArgumentCaptor.forClass(lms.common.kernel.DomainEvent.class);
        verify(events).publish(c.capture());
        UserSessionsRevokedEvent e = (UserSessionsRevokedEvent) c.getValue();
        assertThat(e.sessionId()).isNull();
        assertThat(e.revokedCount()).isEqualTo(4);

        org.mockito.Mockito.clearInvocations(events);
        assertThat(uc.execute(userId, tenantA)).isEqualTo(4); // luồng cũ không có người thao tác: không audit
        verify(events, never()).publish(any());
    }

    @Test
    void luongThuHoiHangLoatChuYeuVanGoiRevokeAll() {
        // Đổi mật khẩu / đặt lại mật khẩu / RoleRevoked dùng revokeAllByUserId (BULK), không bị đổi bởi ADR-0017
        SaveRefreshTokenPort p = mock(SaveRefreshTokenPort.class);
        new lms.identity.auth.application.RevokeUserSessionsUseCase(p).execute(userId);
        verify(p).revokeAllByUserId(eq(userId));
    }
}

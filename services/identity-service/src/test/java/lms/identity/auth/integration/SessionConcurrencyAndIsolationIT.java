package lms.identity.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lms.identity.auth.application.AuthDtos.TokenResponse;
import lms.identity.auth.application.ClientInfo;
import lms.identity.auth.application.GetCredentialInfoUseCase;
import lms.identity.auth.application.ListTenantUserSessionsUseCase;
import lms.identity.auth.application.PublishAuthEventPort;
import lms.identity.auth.application.PurgeExpiredRefreshTokensUseCase;
import lms.identity.auth.application.RefreshTokenUseCase;
import lms.identity.auth.application.RegisterCredentialUseCase;
import lms.identity.auth.application.ResetCredentialPasswordUseCase;
import lms.identity.auth.application.RevokeTenantSessionsUseCase;
import lms.identity.auth.application.RevokeTenantUserSessionUseCase;
import lms.identity.auth.application.TenantAccessPort;
import lms.identity.auth.application.TokenIssuerPort;
import lms.identity.auth.application.TokenService;
import lms.identity.auth.application.UnlockCredentialUseCase;
import lms.identity.auth.application.UserAccessPort;
import lms.identity.auth.domain.InvalidRefreshTokenException;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.RefreshTokenSupersededException;
import lms.identity.auth.domain.TokenChannel;
import lms.identity.auth.domain.UserCredential;
import lms.identity.auth.domain.UserCredentialRepository;
import lms.identity.auth.domain.UserSessionsRevokedEvent;
import lms.identity.auth.domain.UserSessionsViewedEvent;
import lms.identity.auth.infrastructure.client.InProcessAccountCredentialAdapter;
import lms.identity.auth.infrastructure.persistence.AuthPersistenceAdapter;
import lms.identity.auth.infrastructure.persistence.CredentialJpaRepository;
import lms.identity.auth.infrastructure.persistence.RefreshTokenJpaRepository;
import lms.identity.auth.infrastructure.security.SecureRefreshTokenCodec;
import lms.identity.tenant.application.TenantChoice;
import lms.identity.user.application.ListUserSessionsUseCase;
import lms.identity.user.application.RevokeUserSessionUseCase;
import lms.identity.user.application.RevokeUserSessionsInTenantUseCase;
import lms.identity.user.application.SessionCaller;
import lms.identity.user.application.TargetAdminLookupPort;
import lms.identity.user.application.UserSessionView;
import lms.identity.user.domain.UserNotFoundException;
import lms.identity.user.domain.UserRepository;
import lms.identity.user.domain.UserSessionNotFoundException;
import lms.tenant.TenantAutoConfiguration;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-0017, kiểm thử bổ sung (tester). Cần Docker. Chạy:
 * {@code mvn -pl services/identity-service -am test -Dtest=SessionConcurrencyAndIsolationIT
 * -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44}.
 *
 * <p>Test KHÔNG nằm trong transaction ({@code NOT_SUPPORTED}) nên mỗi lời gọi adapter có transaction thật, nhiều luồng
 * chạy song song thật trên Postgres thật (Flyway chạy toàn bộ migration identity). Dùng các use case thật
 * ({@link TokenService}, {@link RefreshTokenUseCase}, các use case phiên của auth và user) trên adapter persistence thật;
 * chỉ các cổng ngoài ngữ cảnh (JWT, quyền, tenant) là giả.
 */
@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration/identity"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(TenantAutoConfiguration.class)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(SessionConcurrencyAndIsolationIT.Config.class)
class SessionConcurrencyAndIsolationIT {

    @TestConfiguration
    static class Config {
        @Bean
        AuthPersistenceAdapter adapter(CredentialJpaRepository credentials, RefreshTokenJpaRepository tokens) {
            return new AuthPersistenceAdapter(credentials, tokens, CLOCK);
        }
    }

    /** Đồng hồ chỉnh tay được, dùng chung cho adapter và use case. */
    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    static final MutableClock CLOCK = new MutableClock();

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    AuthPersistenceAdapter adapter;
    @Autowired
    JdbcTemplate jdbc;

    private final List<TokenIssuerPort.Claims> issuedClaims = Collections.synchronizedList(new ArrayList<>());

    // ---------------------------------------------------------------- dựng use case thật trên adapter thật

    private TokenService tokenService(UUID userId) {
        UserAccessPort access = mock(UserAccessPort.class);
        when(access.fetch(any())).thenAnswer(i -> new UserAccessPort.UserAccess(i.getArgument(0), List.of(), List.of(), false));
        TokenIssuerPort issuer = claims -> {
            issuedClaims.add(claims);
            return new TokenIssuerPort.IssuedToken("jwt-" + UUID.randomUUID(), 900);
        };
        return new TokenService(access, issuer, new SecureRefreshTokenCodec(), adapter, CLOCK, Duration.ofDays(7),
                tenantAccess());
    }

    private TenantAccessPort tenantAccess() {
        TenantAccessPort port = mock(TenantAccessPort.class);
        when(port.selectForLogin(any(), any(), anyBoolean()))
                .thenAnswer(i -> new TenantChoice(defaultTenant.get(), List.of()));
        when(port.verify(any(), any())).thenAnswer(i -> new TenantChoice(i.getArgument(1), List.of()));
        when(port.modules(any())).thenReturn(List.of());
        when(port.available(any())).thenReturn(List.of());
        return port;
    }

    private final ThreadLocalHolder defaultTenant = new ThreadLocalHolder();

    static final class ThreadLocalHolder {
        volatile UUID value;
        UUID get() { return value; }
    }

    private RefreshTokenUseCase refreshUseCase(UserCredential credential, TokenService tokens) {
        UserCredentialRepository creds = mock(UserCredentialRepository.class);
        when(creds.findByUserId(credential.userId())).thenReturn(java.util.Optional.of(credential));
        return new RefreshTokenUseCase(adapter, adapter, creds, new SecureRefreshTokenCodec(), tokens, tenantAccess(),
                CLOCK, Duration.ofSeconds(30));
    }

    private static UserCredential credential(UUID userId) {
        return UserCredential.register(userId, "user-" + userId, "hash", null);
    }

    private TokenResponse login(TokenService tokens, UserCredential c, UUID tenant, String ip, String ua) {
        defaultTenant.value = tenant;
        return tokens.issueFor(c, null, TokenChannel.STAFF, new ClientInfo(ip, ua));
    }

    private int activeInFamily(UUID sessionId) {
        return jdbc.queryForObject("select count(*) from refresh_tokens where session_id = ? and revoked = false",
                Integer.class, sessionId);
    }

    private UUID sessionOfRaw(String raw) {
        return adapter.findByTokenHash(new SecureRefreshTokenCodec().hash(raw)).orElseThrow().sessionId();
    }

    // ---------------------------------------------------------------- (a) hai refresh song song thật

    @Test
    void haiRefreshSongSongCungMotTokenDungMotThanhCongMotSuperseded() throws Exception {
        UUID user = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UserCredential c = credential(user);
        TokenService tokens = tokenService(user);
        RefreshTokenUseCase refresh = refreshUseCase(c, tokens);
        int rounds = 30;
        int bothFailed = 0;
        for (int round = 0; round < rounds; round++) {
            TokenResponse first = login(tokens, c, tenant, "10.0.0.1", "Mozilla/5.0 Chrome/120");
            UUID sid = sessionOfRaw(first.refreshToken());

            List<Object> results = runConcurrently(2, () -> {
                try {
                    return refresh.execute(first.refreshToken(), TokenChannel.STAFF, tenant,
                            new ClientInfo("10.0.0.2", "Mozilla/5.0 Chrome/120"));
                } catch (Exception e) {
                    return e;
                }
            });

            long ok = results.stream().filter(r -> r instanceof TokenResponse).count();
            List<Object> failed = results.stream().filter(r -> !(r instanceof TokenResponse)).toList();
            assertThat(ok).as("vòng %d: đúng một thành công, kết quả=%s", round, results).isEqualTo(1);
            assertThat(failed).hasSize(1);
            assertThat(failed.get(0)).as("vòng %d", round).isInstanceOf(RefreshTokenSupersededException.class);
            assertThat(activeInFamily(sid)).as("vòng %d: họ phiên không được có hai token hoạt động", round).isEqualTo(1);
            TokenResponse winner = (TokenResponse) results.stream().filter(r -> r instanceof TokenResponse).findFirst().get();
            // token thắng dùng tiếp được (không bị loser thu hồi oan)
            assertThat(adapter.findByTokenHash(new SecureRefreshTokenCodec().hash(winner.refreshToken())).orElseThrow()
                    .isRevoked()).isFalse();
        }
        assertThat(bothFailed).isZero();
    }

    @Test
    void tamRefreshSongSongCungMotTokenVanChiMotThanhCong() throws Exception {
        UUID user = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UserCredential c = credential(user);
        TokenService tokens = tokenService(user);
        RefreshTokenUseCase refresh = refreshUseCase(c, tokens);
        TokenResponse first = login(tokens, c, tenant, "10.0.0.1", "Firefox");
        UUID sid = sessionOfRaw(first.refreshToken());

        List<Object> results = runConcurrently(8, () -> {
            try {
                return refresh.execute(first.refreshToken(), TokenChannel.STAFF, tenant, null);
            } catch (Exception e) {
                return e;
            }
        });

        assertThat(results.stream().filter(r -> r instanceof TokenResponse).count()).as("%s", results).isEqualTo(1);
        assertThat(results.stream().filter(r -> r instanceof RefreshTokenSupersededException).count()).isEqualTo(7);
        assertThat(activeInFamily(sid)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- (c) hai thiết bị độc lập

    @Test
    void replayTokenCuCuaThietBiAChiThuHoiHoPhienABVanDungDuoc() {
        UUID user = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UserCredential c = credential(user);
        TokenService tokens = tokenService(user);
        RefreshTokenUseCase refresh = refreshUseCase(c, tokens);

        TokenResponse a1 = login(tokens, c, tenant, "1.1.1.1", "Chrome Windows");
        TokenResponse b1 = login(tokens, c, tenant, "2.2.2.2", "Safari iPhone");
        UUID sidA = sessionOfRaw(a1.refreshToken());
        UUID sidB = sessionOfRaw(b1.refreshToken());
        assertThat(sidA).isNotEqualTo(sidB);

        TokenResponse a2 = refresh.execute(a1.refreshToken(), TokenChannel.STAFF, tenant, null);
        // ngoài cửa sổ ân hạn: replay token cũ của A là dấu hiệu bị đánh cắp
        CLOCK.advance(Duration.ofSeconds(31));
        assertThatThrownBy(() -> refresh.execute(a1.refreshToken(), TokenChannel.STAFF, tenant, null))
                .isInstanceOf(InvalidRefreshTokenException.class)
                .isNotInstanceOf(RefreshTokenSupersededException.class);

        assertThat(activeInFamily(sidA)).as("họ A bị thu hồi hết").isZero();
        assertThatThrownBy(() -> refresh.execute(a2.refreshToken(), TokenChannel.STAFF, tenant, null))
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(activeInFamily(sidB)).as("thiết bị B không bị ảnh hưởng").isEqualTo(1);
        TokenResponse b2 = refresh.execute(b1.refreshToken(), TokenChannel.STAFF, tenant, null);
        assertThat(b2.refreshToken()).isNotBlank();
        assertThat(activeInFamily(sidB)).isEqualTo(1);
        // claim sid: access token của B mang đúng sidB, lần refresh giữ nguyên họ phiên
        assertThat(issuedClaims).filteredOn(cl -> cl.sessionId() != null && cl.sessionId().equals(sidB)).hasSize(2);
    }

    @Test
    void ranhGioiAnHan30GiayVaHoPhienBiThuHoiKhiQuaAnHan() {
        UUID user = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UserCredential c = credential(user);
        TokenService tokens = tokenService(user);
        RefreshTokenUseCase refresh = refreshUseCase(c, tokens);

        TokenResponse t1 = login(tokens, c, tenant, null, null);
        UUID sid = sessionOfRaw(t1.refreshToken());
        refresh.execute(t1.refreshToken(), TokenChannel.STAFF, tenant, null); // t1 bị xoay ở thời điểm T
        // Đúng T + 30s: còn trong ân hạn (ADR: now - revoked_at <= grace)
        CLOCK.advance(Duration.ofSeconds(30));
        assertThatThrownBy(() -> refresh.execute(t1.refreshToken(), TokenChannel.STAFF, tenant, null))
                .isInstanceOf(RefreshTokenSupersededException.class);
        assertThat(activeInFamily(sid)).isEqualTo(1);
        // T + 30s + 1ms: ngoài ân hạn, thu hồi họ
        CLOCK.advance(Duration.ofMillis(1));
        assertThatThrownBy(() -> refresh.execute(t1.refreshToken(), TokenChannel.STAFF, tenant, null))
                .isNotInstanceOf(RefreshTokenSupersededException.class)
                .isInstanceOf(InvalidRefreshTokenException.class);
        assertThat(activeInFamily(sid)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where session_id = ? "
                + "and revoked_reason = 'REUSE_DETECTED'", Integer.class, sid)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- (d) IDOR / tenant qua use case admin thật

    private record AdminWiring(ListUserSessionsUseCase list, RevokeUserSessionUseCase revokeOne,
                               RevokeUserSessionsInTenantUseCase revokeAll, List<Object> events) {
    }

    private AdminWiring adminWiring(boolean targetIsTenantAdmin, UUID memberOfTenant, UUID... members) {
        List<Object> events = Collections.synchronizedList(new ArrayList<>());
        PublishAuthEventPort publisher = mock(PublishAuthEventPort.class);
        doAnswer(i -> events.add(i.getArgument(0))).when(publisher).publish(any());
        InProcessAccountCredentialAdapter port = new InProcessAccountCredentialAdapter(
                mock(RegisterCredentialUseCase.class), mock(GetCredentialInfoUseCase.class),
                mock(ResetCredentialPasswordUseCase.class), mock(UnlockCredentialUseCase.class),
                new RevokeTenantSessionsUseCase(adapter, publisher, CLOCK),
                new ListTenantUserSessionsUseCase(adapter, publisher, CLOCK),
                new RevokeTenantUserSessionUseCase(adapter, publisher, CLOCK));
        UserRepository repo = mock(UserRepository.class);
        when(repo.hasMembership(any(), eq(memberOfTenant))).thenAnswer(i -> List.of(members).contains(i.getArgument(0)));
        TargetAdminLookupPort lookup = userId -> targetIsTenantAdmin;
        return new AdminWiring(new ListUserSessionsUseCase(repo, port, lookup),
                new RevokeUserSessionUseCase(repo, port, lookup),
                new RevokeUserSessionsInTenantUseCase(repo, port, lookup), events);
    }

    @Test
    void adminTenantAKhongThayKhongThuHoiDuocPhienTenantBPhienNenTangHayPhienNguoiKhac() {
        UUID victim = UUID.randomUUID();
        UUID colleague = UUID.randomUUID();
        UUID adminA = UUID.randomUUID();
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UserCredential vc = credential(victim);
        UserCredential cc = credential(colleague);
        TokenService tokensV = tokenService(victim);
        TokenService tokensC = tokenService(colleague);

        TokenResponse inA = login(tokensV, vc, tenantA, "9.9.9.9", "Chrome Windows");
        TokenResponse inB = login(tokensV, vc, tenantB, "8.8.8.8", "Firefox Linux");
        adapter.save(RefreshToken.issueForPlatform(victim, "platform-" + victim, CLOCK.instant(), Duration.ofDays(7))
                .withDevice("Edge", "7.7.7.7"));
        TokenResponse colleagueInA = login(tokensC, cc, tenantA, "6.6.6.6", "Safari");
        UUID sidInA = sessionOfRaw(inA.refreshToken());
        UUID sidInB = sessionOfRaw(inB.refreshToken());
        UUID sidPlatform = adapter.findByTokenHash("platform-" + victim).orElseThrow().sessionId();
        UUID sidColleague = sessionOfRaw(colleagueInA.refreshToken());

        AdminWiring w = adminWiring(false, tenantA, victim, colleague, adminA);
        SessionCaller admin = new SessionCaller(adminA, true, false);

        TenantContext.runAs(tenantA, () -> {
            List<UserSessionView> listed = w.list().execute(admin, victim);
            assertThat(listed).extracting(UserSessionView::sessionId).containsExactly(sidInA);
            // IP đầy đủ cho admin (Q6), nhưng sự kiện audit không chứa IP/thiết bị
            assertThat(listed.get(0).ipAddress()).isEqualTo("9.9.9.9");

            for (UUID foreign : List.of(sidInB, sidPlatform, sidColleague, UUID.randomUUID())) {
                assertThatThrownBy(() -> w.revokeOne().execute(admin, victim, foreign))
                        .as("sessionId %s", foreign).isInstanceOf(UserSessionNotFoundException.class);
            }
            // sessionId của chính victim nhưng {id} là đồng nghiệp: cũng 404 chung
            assertThatThrownBy(() -> w.revokeOne().execute(admin, colleague, sidInA))
                    .isInstanceOf(UserSessionNotFoundException.class);
        });
        assertThat(activeInFamily(sidInB)).isEqualTo(1);
        assertThat(activeInFamily(sidPlatform)).isEqualTo(1);
        assertThat(activeInFamily(sidColleague)).isEqualTo(1);
        assertThat(activeInFamily(sidInA)).isEqualTo(1);
        assertThat(w.events().stream().filter(e -> e instanceof UserSessionsRevokedEvent)).isEmpty();

        // người dùng không phải thành viên tenant A (id lạ) hoặc admin ở tenant B nhìn vào A: 404 USER_NOT_FOUND
        TenantContext.runAs(tenantB, () -> assertThatThrownBy(() -> w.list().execute(admin, victim))
                .isInstanceOf(UserNotFoundException.class));

        // thu hồi tất cả trong tenant A không đụng tenant B, nền tảng, người khác
        TenantContext.runAs(tenantA, () -> assertThat(w.revokeAll().execute(admin, victim)).isEqualTo(1));
        assertThat(activeInFamily(sidInA)).isZero();
        assertThat(activeInFamily(sidInB)).isEqualTo(1);
        assertThat(activeInFamily(sidPlatform)).isEqualTo(1);
        assertThat(activeInFamily(sidColleague)).isEqualTo(1);
        assertThat(w.events()).filteredOn(e -> e instanceof UserSessionsRevokedEvent).hasSize(1);
    }

    @Test
    void doiTenantLamPhienBienKhoiDanhSachTenantCuVaXuatHienOTenantMoi() {
        UUID user = UUID.randomUUID();
        UUID adminA = UUID.randomUUID();
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UserCredential c = credential(user);
        TokenService tokens = tokenService(user);
        TokenResponse inA = login(tokens, c, tenantA, "5.5.5.5", "Chrome Windows");
        UUID sid = sessionOfRaw(inA.refreshToken());
        RefreshToken current = adapter.findByTokenHash(new SecureRefreshTokenCodec().hash(inA.refreshToken())).orElseThrow();

        tokens.issueForTenant(c, new TenantChoice(tenantB, List.of()), current, null);

        AdminWiring w = adminWiring(false, tenantA, user, adminA);
        AdminWiring wb = adminWiring(false, tenantB, user, adminA);
        SessionCaller admin = new SessionCaller(adminA, true, false);
        TenantContext.runAs(tenantA, () -> {
            assertThat(w.list().execute(admin, user)).isEmpty();
            assertThatThrownBy(() -> w.revokeOne().execute(admin, user, sid)).isInstanceOf(UserSessionNotFoundException.class);
        });
        TenantContext.runAs(tenantB, () -> {
            assertThat(wb.list().execute(admin, user)).extracting(UserSessionView::sessionId).containsExactly(sid);
            // thiết bị/IP được chép từ token cũ khi không có ClientInfo mới
            assertThat(wb.list().execute(admin, user).get(0).ipAddress()).isEqualTo("5.5.5.5");
        });
        assertThat(activeInFamily(sid)).isEqualTo(1);
    }

    @Test
    void auditChiKhiXemHoacThuHoiPhienNguoiKhacVaKhongChuaIpThietBi() {
        UUID target = UUID.randomUUID();
        UUID adminA = UUID.randomUUID();
        UUID tenantA = UUID.randomUUID();
        UserCredential c = credential(target);
        TokenService tokens = tokenService(target);
        login(tokens, c, tenantA, "4.4.4.4", "Chrome Windows");

        AdminWiring w = adminWiring(false, tenantA, target, adminA);
        SessionCaller admin = new SessionCaller(adminA, true, false);
        SessionCaller selfTarget = new SessionCaller(target, false, false);
        TenantContext.runAs(tenantA, () -> {
            w.list().execute(selfTarget, target); // tự xem
            assertThat(w.events()).isEmpty();
            w.list().execute(admin, target); // xem người khác
        });
        assertThat(w.events()).hasSize(1);
        UserSessionsViewedEvent viewed = (UserSessionsViewedEvent) w.events().get(0);
        assertThat(viewed.actorId()).isEqualTo(adminA);
        assertThat(viewed.targetUserId()).isEqualTo(target);
        assertThat(viewed.toString()).doesNotContain("4.4.4.4").doesNotContain("Chrome");
    }

    @Test
    void tuThuHoiPhienCuaMinhQuaApiAdminKhongPhatSuKienAudit_ADR_2_9_4() {
        UUID admin = UUID.randomUUID();
        UUID tenantA = UUID.randomUUID();
        UserCredential c = credential(admin);
        TokenService tokens = tokenService(admin);
        TokenResponse t = login(tokens, c, tenantA, "3.3.3.3", "Chrome Windows");
        UUID sid = sessionOfRaw(t.refreshToken());

        AdminWiring w = adminWiring(true, tenantA, admin);
        SessionCaller self = new SessionCaller(admin, true, false);
        TenantContext.runAs(tenantA, () -> {
            w.revokeOne().execute(self, admin, sid);
        });
        // ADR 2.9.4: "Tự xem hoặc tự thu hồi phiên của mình không phát sự kiện ... qua API admin với {id} là chính mình (G5)"
        assertThat(w.events()).as("tự thu hồi một phiên của mình qua API admin không được phát UserSessionsRevokedEvent")
                .isEmpty();

        login(tokens, c, tenantA, "3.3.3.3", "Chrome Windows");
        TenantContext.runAs(tenantA, () -> w.revokeAll().execute(self, admin));
        assertThat(w.events()).as("tự thu hồi tất cả qua API admin không được phát sự kiện").isEmpty();
    }

    // ---------------------------------------------------------------- (e) job dọn theo lô

    @Test
    void jobDonXoaTheoLoDungDongHetHanQuaMotNgay() {
        UUID user = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        Instant now = CLOCK.instant();
        List<String> expiredHashes = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            String h = "purge-old-" + user + "-" + i;
            expiredHashes.add(h);
            Instant exp = now.minus(Duration.ofDays(1)).minus(Duration.ofMinutes(5 + i)); // quá 1 ngày
            adapter.save(RefreshToken.restore(UUID.randomUUID(), user, tenant, false, h, exp, i % 2 == 0,
                    exp.minus(Duration.ofDays(7)), TokenChannel.STAFF, UUID.randomUUID(), exp.minus(Duration.ofDays(7)),
                    null, null, null, null));
        }
        Instant recentlyExpired = now.minus(Duration.ofHours(12));
        adapter.save(RefreshToken.restore(UUID.randomUUID(), user, tenant, false, "purge-recent-" + user, recentlyExpired,
                false, recentlyExpired.minus(Duration.ofDays(7)), TokenChannel.STAFF, UUID.randomUUID(),
                recentlyExpired.minus(Duration.ofDays(7)), null, null, null, null));
        adapter.save(RefreshToken.issueForTenant(user, tenant, "purge-live-" + user, now, Duration.ofDays(7)));
        // đúng ngưỡng: hết hạn đúng now - 1 ngày không bị xóa (điều kiện expires_at < cutoff)
        Instant boundary = now.minus(Duration.ofDays(1));
        adapter.save(RefreshToken.restore(UUID.randomUUID(), user, tenant, false, "purge-boundary-" + user, boundary,
                false, boundary.minus(Duration.ofDays(7)), TokenChannel.STAFF, UUID.randomUUID(),
                boundary.minus(Duration.ofDays(7)), null, null, null, null));

        int before = jdbc.queryForObject("select count(*) from refresh_tokens", Integer.class);
        int deleted = new PurgeExpiredRefreshTokensUseCase(adapter, CLOCK, Duration.ofDays(1), 3).execute();

        int expiredBeyondOneDay = jdbc.queryForObject(
                "select count(*) from refresh_tokens where expires_at < ?", Integer.class,
                java.sql.Timestamp.from(now.minus(Duration.ofDays(1))));
        assertThat(expiredBeyondOneDay).isZero();
        assertThat(deleted).as("xóa theo lô 3 dòng/lần: phải xóa đủ 7 dòng hết hạn của test này (cộng dòng cũ của test khác nếu có)")
                .isGreaterThanOrEqualTo(7);
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens", Integer.class)).isEqualTo(before - deleted);
        for (String h : expiredHashes) assertThat(adapter.findByTokenHash(h)).as(h).isEmpty();
        assertThat(adapter.findByTokenHash("purge-recent-" + user)).isPresent();
        assertThat(adapter.findByTokenHash("purge-live-" + user)).isPresent();
        assertThat(adapter.findByTokenHash("purge-boundary-" + user)).isPresent();
    }

    // ---------------------------------------------------------------- tiện ích song song

    private static List<Object> runConcurrently(int threads, Callable<Object> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> f : futures) results.add(f.get(30, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}

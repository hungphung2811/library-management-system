package lms.identity.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lms.identity.auth.domain.RefreshToken;
import lms.identity.auth.domain.RefreshTokenSupersededException;
import lms.identity.auth.domain.RevokeReason;
import lms.identity.auth.infrastructure.persistence.AuthPersistenceAdapter;
import lms.identity.auth.infrastructure.persistence.CredentialJpaRepository;
import lms.identity.auth.infrastructure.persistence.RefreshTokenJpaRepository;
import lms.tenant.TenantAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cần Docker. Chạy: {@code mvn -pl services/identity-service -am test -Dtest=SessionFamilyPersistenceIT
 * -Dsurefire.failIfNoSpecifiedTests=false} (Docker 29 thêm {@code -DargLine=-Dapi.version=1.44}). Flyway chạy V24 lên
 * schema thật (cột mới NOT NULL), rồi kiểm xoay vòng có điều kiện, thu hồi theo họ, IDOR theo tenant và dọn dẹp.
 * Giới hạn: DataJpaTest chạy trong một transaction nên race "song song thật bằng hai luồng" chưa được kiểm ở đây
 * (kiểm tuần tự: lần xoay thứ hai cùng token thất bại và không chèn token mới).
 */
@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration/identity"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(TenantAutoConfiguration.class)
@Testcontainers
@Import(SessionFamilyPersistenceIT.Config.class)
class SessionFamilyPersistenceIT {

    @TestConfiguration
    static class Config {
        @Bean
        AuthPersistenceAdapter adapter(CredentialJpaRepository credentials, RefreshTokenJpaRepository tokens) {
            return new AuthPersistenceAdapter(credentials, tokens);
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    AuthPersistenceAdapter adapter;

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private final UUID user = UUID.randomUUID();
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();

    private RefreshToken login(UUID u, UUID tenant, String hash) {
        RefreshToken t = RefreshToken.issueForTenant(u, tenant, hash, now, Duration.ofDays(7)).withDevice("Chrome", "1.1.1.1");
        adapter.save(t);
        return t;
    }

    @Test
    void xoayVongCoDieuKienLanThuHaiCungTokenKhongChenTokenMoi() {
        RefreshToken t1 = login(user, tenantA, "t1");
        RefreshToken t1Copy = adapter.findByTokenHash("t1").orElseThrow();
        t1.revoke(RevokeReason.ROTATED, now);
        RefreshToken t2 = RefreshToken.issueForTenant(user, tenantA, "t2", now, Duration.ofDays(7))
                .inSession(t1.sessionId(), t1.sessionStartedAt());
        adapter.rotate(t1, t2);

        t1Copy.revoke(RevokeReason.ROTATED, now);
        RefreshToken t3 = RefreshToken.issueForTenant(user, tenantA, "t3", now, Duration.ofDays(7))
                .inSession(t1.sessionId(), t1.sessionStartedAt());
        assertThatThrownBy(() -> adapter.rotate(t1Copy, t3)).isInstanceOf(RefreshTokenSupersededException.class);

        assertThat(adapter.findByTokenHash("t3")).isEmpty();
        assertThat(adapter.findActiveByUserId(user, now)).extracting(RefreshToken::tokenHash).containsExactly("t2");
        RefreshToken stored = adapter.findByTokenHash("t1").orElseThrow();
        assertThat(stored.revokedReason()).isEqualTo(RevokeReason.ROTATED);
        assertThat(stored.deviceSummary()).isNull(); // dòng đã xoay không giữ nhãn thiết bị/IP
        assertThat(stored.clientIp()).isNull();
    }

    @Test
    void thuHoiHoPhienKhongDungThietBiKhacVaKhongRevokeAll() {
        RefreshToken a = login(user, tenantA, "a1");
        RefreshToken b = login(user, tenantA, "b1");

        int n = adapter.revokeSession(a.sessionId(), RevokeReason.REUSE_DETECTED, now);

        assertThat(n).isEqualTo(1);
        assertThat(adapter.findByTokenHash("a1").orElseThrow().isRevoked()).isTrue();
        assertThat(adapter.findByTokenHash("b1").orElseThrow().isRevoked()).isFalse();
        assertThat(adapter.revokeSession(a.sessionId(), RevokeReason.LOGOUT, now)).isZero(); // idempotent
    }

    @Test
    void revokeAllExceptGiuPhienHienTaiVaChiDungNguoiDo() {
        RefreshToken keep = login(user, tenantA, "k1");
        login(user, tenantB, "o1");
        adapter.save(RefreshToken.issueForPlatform(user, "pf", now, Duration.ofDays(7)));
        UUID other = UUID.randomUUID();
        login(other, tenantA, "x1");

        int n = adapter.revokeAllExcept(user, keep.sessionId(), now);

        assertThat(n).isEqualTo(2); // o1 (tenant B) và pf (phiên nền tảng): mọi tenant, mọi phiên trừ phiên hiện tại
        assertThat(adapter.findByTokenHash("pf").orElseThrow().isRevoked()).isTrue();
        assertThat(adapter.findByTokenHash("k1").orElseThrow().isRevoked()).isFalse();
        assertThat(adapter.findByTokenHash("o1").orElseThrow().isRevoked()).isTrue();
        assertThat(adapter.findByTokenHash("x1").orElseThrow().isRevoked()).isFalse();
    }

    @Test
    void idorPhienAdminLocDuUserTenantSessionTrongMotCauSql() {
        UUID victim = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        RefreshToken inA = login(victim, tenantA, "va");
        RefreshToken inB = login(victim, tenantB, "vb");
        RefreshToken platform = RefreshToken.issueForPlatform(victim, "vp", now, Duration.ofDays(7));
        adapter.save(platform);
        RefreshToken others = login(otherUser, tenantA, "oa");

        // Liệt kê: chỉ phiên của tenant A, không có phiên tenant B hay nền tảng
        List<RefreshToken> listed = adapter.findActiveByUserIdAndTenantId(victim, tenantA, now);
        assertThat(listed).extracting(RefreshToken::tokenHash).containsExactly("va");

        // Thu hồi một phiên: sessionId của tenant khác, nền tảng, người khác (id người dùng sai) đều 0 dòng
        assertThat(adapter.revokeSessionInTenant(victim, tenantA, inB.sessionId())).isZero();
        assertThat(adapter.revokeSessionInTenant(victim, tenantA, platform.sessionId())).isZero();
        assertThat(adapter.revokeSessionInTenant(victim, tenantA, others.sessionId())).isZero();
        assertThat(adapter.revokeSessionInTenant(otherUser, tenantA, inA.sessionId())).isZero();
        assertThat(adapter.revokeSessionInTenant(victim, null, inA.sessionId())).isZero();
        for (String h : List.of("va", "vb", "vp", "oa")) {
            assertThat(adapter.findByTokenHash(h).orElseThrow().isRevoked()).as(h).isFalse();
        }

        // Thu hồi tất cả trong tenant A không đụng phiên tenant B và nền tảng
        assertThat(adapter.revokeAllInTenant(victim, tenantA)).isEqualTo(1);
        assertThat(adapter.findByTokenHash("va").orElseThrow().isRevoked()).isTrue();
        assertThat(adapter.findByTokenHash("vb").orElseThrow().isRevoked()).isFalse();
        assertThat(adapter.findByTokenHash("vp").orElseThrow().isRevoked()).isFalse();

        // Thu hồi đúng phiên: khớp đủ ba điều kiện
        assertThat(adapter.revokeSessionInTenant(victim, tenantB, inB.sessionId())).isEqualTo(1);
    }

    @Test
    void jobDonXoaDungDongHetHanQuaMotNgayGiuDongThuHoiChuaHetHan() {
        Instant longAgo = now.minus(Duration.ofDays(3));
        adapter.save(RefreshToken.restore(UUID.randomUUID(), user, tenantA, false, "old-expired", longAgo.plusSeconds(60),
                false, longAgo, lms.identity.auth.domain.TokenChannel.STAFF, UUID.randomUUID(), longAgo, null, null, null,
                null));
        RefreshToken revokedButLive = login(user, tenantA, "revoked-live");
        revokedButLive.revoke(RevokeReason.ROTATED, now);
        adapter.rotate(revokedButLive, RefreshToken.issueForTenant(user, tenantA, "live", now, Duration.ofDays(7))
                .inSession(revokedButLive.sessionId(), revokedButLive.sessionStartedAt()));

        int deleted = adapter.deleteExpiredBefore(now.minus(Duration.ofDays(1)), 100);

        assertThat(deleted).isEqualTo(1);
        assertThat(adapter.findByTokenHash("old-expired")).isEmpty();
        assertThat(adapter.findByTokenHash("revoked-live")).isPresent();
        assertThat(adapter.findByTokenHash("live")).isPresent();
    }
}

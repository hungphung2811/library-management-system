package lms.sales.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.observability.log.IntegrationLog;
import lms.observability.log.LogChannel;
import lms.sales.oplog.application.OperationalLogStore;
import lms.sales.oplog.application.OperationalLogStore.Criteria;
import lms.sales.oplog.domain.OperationalLogEvent;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Log vận hành lưu DB (ADR-0029) trên PostgreSQL thật, chạy bằng vai trò sales_app (NOBYPASSRLS): tenant A không đọc
 * được log của B; platform (phạm vi ALL) đọc được mọi dòng; dòng tenant NULL chỉ platform đọc; vai trò ứng dụng không chèn
 * được dòng cho tenant khác hay dòng NULL khi không ở phạm vi platform; dọn dữ liệu cũ; và đường đi thật từ IntegrationLog
 * qua appender vào bảng. Cần Docker:
 * {@code mvn -o -pl services/sales-service -am test -Dtest=OperationalLogIT -Dsurefire.failIfNoSpecifiedTests=false
 * -DargLine=-Dapi.version=1.44}.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/sales",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "lms.sales.events.enabled=false",
        "lms.sales.logs.persist.enabled=true",
        "lms.sales.logs.purge.enabled=false"})
@Testcontainers
class OperationalLogIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired OperationalLogStore store;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    final UUID tenantA = UUID.randomUUID();
    final UUID tenantB = UUID.randomUUID();
    final Instant now = Instant.now();

    @BeforeEach
    @AfterEach
    void clean() throws SQLException {
        TenantContext.clear();
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            s.execute("delete from integration_log_events");
        }
    }

    private OperationalLogEvent event(UUID tenant, String channel, String op, Instant at) {
        return new OperationalLogEvent(UUID.randomUUID(), tenant, at, channel, "momo", "integration-call", "WARN", op,
                "FAILURE", 502, 10L, "SERVER_ERROR", "IOException", null, "cid", null, "ORD*****1234",
                Map.of("partnerCode", "99"));
    }

    private Criteria all(UUID tenantFilter) {
        return new Criteria(tenantFilter, List.of("INTEGRATION_OUTBOUND", "INTEGRATION_INBOUND", "SYSTEM"), null, null,
                null, false, now.minus(Duration.ofDays(2)), now.plus(Duration.ofDays(1)));
    }

    private List<String> ops(Criteria c) {
        return store.search(c, 0, 100).items().stream().map(OperationalLogEvent::op).toList();
    }

    @Test
    void tenantChiThayLogCuaMinhPlatformThayHetVaDongKhongTenantChiPlatform() {
        store.saveBatch(List.of(event(tenantA, "INTEGRATION_OUTBOUND", "a1", now), event(tenantA, "SYSTEM", "a2", now),
                event(tenantB, "INTEGRATION_OUTBOUND", "b1", now), event(null, "INTEGRATION_INBOUND", "n1", now)));

        assertThat(TenantContext.callAs(tenantA, () -> ops(all(null)))).containsExactlyInAnyOrder("a1", "a2");
        assertThat(TenantContext.callAs(tenantB, () -> ops(all(null)))).containsExactly("b1");
        // tenant B cố lọc theo tenant A hoặc theo NULL vẫn không thấy gì (RLS chặn trước điều kiện lọc)
        assertThat(TenantContext.callAs(tenantB, () -> ops(all(tenantA)))).isEmpty();

        TenantContext.bindAllTenants();
        try {
            assertThat(ops(all(null))).containsExactlyInAnyOrder("a1", "a2", "b1", "n1");
            assertThat(ops(all(tenantB))).containsExactly("b1");
            assertThat(store.search(all(null), 0, 2).total()).isEqualTo(4);
        } finally {
            TenantContext.clear();
        }
        // không có tenant lẫn phạm vi platform: fail-closed
        assertThat(ops(all(null))).isEmpty();
    }

    @Test
    void sapMoiNhatTruocLocTheoKenhVaTrang() {
        store.saveBatch(List.of(event(tenantA, "INTEGRATION_OUTBOUND", "old", now.minusSeconds(60)),
                event(tenantA, "INTEGRATION_OUTBOUND", "new", now), event(tenantA, "SYSTEM", "sys", now)));
        Criteria outbound = new Criteria(null, List.of("INTEGRATION_OUTBOUND"), null, null, null, false,
                now.minus(Duration.ofDays(1)), now.plusSeconds(10));
        TenantContext.set(tenantA);
        assertThat(ops(outbound)).containsExactly("new", "old");
        assertThat(store.search(outbound, 1, 1).items()).extracting(OperationalLogEvent::op).containsExactly("old");
    }

    @Test
    void vaiTroUngDungKhongChenDuocDongChoTenantKhacHoacDongNullNgoaiPhamViPlatform() throws SQLException {
        String insert = "insert into integration_log_events (id, tenant_id, occurred_at, channel, event, level) "
                + "values (?, ?, now(), 'SYSTEM', 'system-event', 'WARN')";
        try (Connection c = AppRoleDatabase.appRole(postgres)) {
            setSession(c, tenantA.toString(), "");
            assertThat(exec(c, insert, tenantA)).isEqualTo(1);
            assertThatThrownBy(() -> exec(c, insert, tenantB)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
            assertThatThrownBy(() -> exec(c, insert, null)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
            // tenant không xóa được gì (kể cả dòng của chính mình): chỉ phạm vi platform dọn
            try (Statement s = c.createStatement()) {
                assertThat(s.executeUpdate("delete from integration_log_events")).isZero();
            }
            // phạm vi platform: chèn dòng NULL được, nhưng chèn dòng gắn tenant thì không (không có chính sách khớp)
            setSession(c, "", "all");
            assertThat(exec(c, insert, null)).isEqualTo(1);
            assertThatThrownBy(() -> exec(c, insert, tenantB)).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void donDepXoaDongQuaHanTheoLoVaGiuDongMoi() throws SQLException {
        store.saveBatch(List.of(event(tenantA, "SYSTEM", "old-a", now.minus(Duration.ofDays(40))),
                event(tenantB, "SYSTEM", "old-b", now.minus(Duration.ofDays(31))),
                event(null, "SYSTEM", "old-n", now.minus(Duration.ofDays(45))),
                event(tenantA, "SYSTEM", "fresh", now.minus(Duration.ofDays(1)))));
        int first = store.purgeOlderThan(now.minus(Duration.ofDays(30)), 2);
        int second = store.purgeOlderThan(now.minus(Duration.ofDays(30)), 2);
        assertThat(first).isEqualTo(2);
        assertThat(second).isEqualTo(1);
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select op from integration_log_events")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("fresh");
            assertThat(rs.next()).isFalse();
        }
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void duongDiThatIntegrationLogQuaAppenderVaoBangVaKhongLuuTruongLa() throws Exception {
        IntegrationLog.of(LogChannel.INTEGRATION_OUTBOUND, "itprov").entry().provider("itprov").op("it-op")
                .tenantId(tenantA).reference("ORD-20261119-0001").outcome("FAILURE").httpStatus(503).durationMs(7)
                .errorKind("SERVER_ERROR").anomaly("SLOW").warn();
        IntegrationLog.of(LogChannel.INTEGRATION_INBOUND, "itsrc").entry().provider("itsrc").op("it-in")
                .outcome("REJECTED").httpStatus(401).clientIp("203.0.113.77").info();
        long deadline = System.currentTimeMillis() + 15_000;
        int rows = 0;
        while (System.currentTimeMillis() < deadline && rows < 2) {
            Thread.sleep(200);
            try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("select count(*) from integration_log_events where op in ('it-op','it-in')")) {
                rs.next();
                rows = rs.getInt(1);
            }
        }
        assertThat(rows).isEqualTo(2);
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select tenant_id, channel, provider, http_status, anomaly_type, reference, "
                     + "extra::text from integration_log_events where op = 'it-op'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getObject(1, UUID.class)).isEqualTo(tenantA);
            assertThat(rs.getString(2)).isEqualTo("INTEGRATION_OUTBOUND");
            assertThat(rs.getString(3)).isEqualTo("itprov");
            assertThat(rs.getInt(4)).isEqualTo(503);
            assertThat(rs.getString(5)).isEqualTo("SLOW");
            assertThat(rs.getString(6)).doesNotContain("20261119");
        }
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select tenant_id, extra::text from integration_log_events where op = 'it-in'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getObject(1)).isNull();
            assertThat(rs.getString(2)).doesNotContain("203.0.113.77");
        }
        assertThat(Clock.systemUTC()).isNotNull();
    }

    private static void setSession(Connection c, String tenant, String scope) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "select set_config('app.tenant_id', ?, false), set_config('app.tenant_scope', ?, false)")) {
            ps.setString(1, tenant);
            ps.setString(2, scope);
            ps.execute();
        }
    }

    private static int exec(Connection c, String sql, UUID tenant) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenant);
            return ps.executeUpdate();
        }
    }
}

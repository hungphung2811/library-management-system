package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-0017 (tester): kiểm V24 trên Postgres thật. Chạy V1-V23, chèn dữ liệu cũ, rồi chạy V24. Cần Docker. Chạy:
 * {@code mvn -pl services/identity-service -am test -Dtest=SessionFamilyMigrationIT
 * -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44}.
 */
@Testcontainers
class SessionFamilyMigrationIT {

    static final String U = "00000000-0000-0000-0000-00000000f001";
    static final String LIVE = "00000000-0000-0000-0000-0000000f0001";
    static final String REVOKED_LIVE = "00000000-0000-0000-0000-0000000f0002";
    static final String EXPIRED_OLD = "00000000-0000-0000-0000-0000000f0003";
    static final String EXPIRED_RECENT = "00000000-0000-0000-0000-0000000f0004";
    static final String EXPIRED_OLD_REVOKED = "00000000-0000-0000-0000-0000000f0005";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @BeforeAll
    static void migrate() throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            // vai trò ứng dụng có trước để callback afterMigrate cấp quyền (như môi trường thật)
            s.execute("CREATE ROLE identity_app LOGIN PASSWORD 'identity_app' NOSUPERUSER NOBYPASSRLS");
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/identity").target("23").load().migrate();
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, revoked, created_at) VALUES "
                    + "('" + LIVE + "', '" + U + "', 'h-live', now() + interval '5 days', false, now() - interval '1 hour'), "
                    + "('" + REVOKED_LIVE + "', '" + U + "', 'h-revoked-live', now() + interval '5 days', true, now() - interval '2 hours'), "
                    + "('" + EXPIRED_OLD + "', '" + U + "', 'h-exp-old', now() - interval '2 days', false, now() - interval '9 days'), "
                    + "('" + EXPIRED_RECENT + "', '" + U + "', 'h-exp-recent', now() - interval '12 hours', false, now() - interval '8 days'), "
                    + "('" + EXPIRED_OLD_REVOKED + "', '" + U + "', 'h-exp-old-rev', now() - interval '3 days', true, now() - interval '10 days')");
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/identity").target("24").load().migrate();
    }

    static Connection conn() throws SQLException {
        return java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    static List<String> column(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = conn(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    @Test
    void dongHetHanQuaMotNgayBiXoaDongHetHanGanDayVaConHanDuocGiu() throws SQLException {
        assertThat(column("select id::text from refresh_tokens where user_id = '" + U + "'"))
                .containsExactlyInAnyOrder(LIVE, REVOKED_LIVE, EXPIRED_RECENT);
    }

    @Test
    void backfillSessionIdBangIdSessionStartedAtBangCreatedAt() throws SQLException {
        assertThat(column("select count(*)::text from refresh_tokens where session_id <> id")).containsExactly("0");
        assertThat(column("select count(*)::text from refresh_tokens where session_started_at <> created_at"))
                .containsExactly("0");
    }

    @Test
    void sessionIdVaSessionStartedAtLaNotNull() throws SQLException {
        assertThat(column("select column_name || '=' || is_nullable from information_schema.columns "
                + "where table_name = 'refresh_tokens' and column_name in ('session_id','session_started_at') "
                + "order by column_name")).containsExactly("session_id=NO", "session_started_at=NO");
        // cột siêu dữ liệu và thu hồi cho phép NULL
        assertThat(column("select column_name || '=' || is_nullable from information_schema.columns "
                + "where table_name = 'refresh_tokens' and column_name in "
                + "('device_summary','client_ip','revoked_at','revoked_reason') order by column_name"))
                .containsExactly("client_ip=YES", "device_summary=YES", "revoked_at=YES", "revoked_reason=YES");
    }

    @Test
    void duLieuCuDaThuHoiCoRevokedReasonBulkVaRevokedAtBangCreatedAtDongConHoatDongThiNull() throws SQLException {
        assertThat(column("select revoked_reason || '|' || (revoked_at = created_at) from refresh_tokens "
                + "where id = '" + REVOKED_LIVE + "'")).containsExactly("BULK|true");
        assertThat(column("select coalesce(revoked_reason, 'null') || '|' || coalesce(revoked_at::text, 'null') "
                + "from refresh_tokens where id = '" + LIVE + "'")).containsExactly("null|null");
        assertThat(column("select coalesce(revoked_reason, 'null') from refresh_tokens where id = '" + EXPIRED_RECENT
                + "'")).containsExactly("null");
    }

    @Test
    void bonChiMucTonTai() throws SQLException {
        assertThat(column("select indexname from pg_indexes where tablename = 'refresh_tokens' "
                + "and indexname like 'idx_refresh_tokens_%'"))
                .contains("idx_refresh_tokens_session_active", "idx_refresh_tokens_user_active",
                        "idx_refresh_tokens_expires_at");
        // hai chỉ mục theo phiên/người dùng là chỉ mục một phần (revoked = false)
        assertThat(column("select indexdef from pg_indexes where indexname = 'idx_refresh_tokens_session_active'").get(0))
                .containsIgnoringCase("WHERE (revoked = false)");
        assertThat(column("select indexdef from pg_indexes where indexname = 'idx_refresh_tokens_user_active'").get(0))
                .containsIgnoringCase("WHERE (revoked = false)");
    }

    @Test
    void vaiTroUngDungChayDuocDeleteVaUpdateTrenBangMoi() throws SQLException {
        try (Connection c = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), "identity_app", "identity_app");
             Statement s = c.createStatement()) {
            assertThat(s.executeUpdate("update refresh_tokens set revoked_reason = 'LOGOUT' where id = '" + EXPIRED_RECENT
                    + "'")).isEqualTo(1);
            assertThat(s.executeUpdate("delete from refresh_tokens where id = '" + EXPIRED_RECENT + "'")).isEqualTo(1);
        }
    }

    @Test
    void migrationV24GhiVaoSoCai() throws SQLException {
        assertThat(column("select success::text from flyway_schema_history where version = '24'")).containsExactly("true");
    }
}

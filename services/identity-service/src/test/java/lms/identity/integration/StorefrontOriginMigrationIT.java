package lms.identity.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cần Docker. Migration V21 (ADR-0013): bảng origin storefront (UNIQUE toàn hệ thống, CHECK dạng chuẩn hóa), lịch sử chỉ
 * thêm (trigger chặn UPDATE/DELETE kể cả chủ bảng), REVOKE cho vai trò ứng dụng và vẫn giữ sau khi chạy lại afterMigrate.
 * Chạy: mvn -pl services/identity-service -am test -Dtest=StorefrontOriginMigrationIT
 * -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44
 */
@Testcontainers
class StorefrontOriginMigrationIT {

    static final String TENANT = "00000000-0000-0000-0000-00000000ab01";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @BeforeAll
    static void migrate() throws SQLException {
        exec("CREATE ROLE identity_app LOGIN PASSWORD 'identity_app' NOSUPERUSER NOBYPASSRLS");
        migrateAll();
        exec("INSERT INTO tenants (id, code, name, status) VALUES ('" + TENANT + "', 'or-mig', 'Tenant', 'ACTIVE')");
    }

    static void migrateAll() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/identity").load().migrate();
    }

    static Connection conn() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    static void exec(String sql) throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    static String one(String sql) throws SQLException {
        try (Connection c = conn(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static String insertOrigin(String origin) {
        return "INSERT INTO tenant_storefront_origins (id, tenant_id, origin) VALUES (gen_random_uuid(), '" + TENANT
                + "', '" + origin + "')";
    }

    @Test
    void khongSeedDuLieuVaMacDinhBatKhiChen() throws Exception {
        assertThat(one("SELECT count(*) FROM tenant_storefront_origins")).isEqualTo("0");
        assertThat(one("SELECT count(*) FROM tenant_storefront_origin_history")).isEqualTo("0");
        exec(insertOrigin("https://default-enabled.vn"));
        assertThat(one("SELECT enabled FROM tenant_storefront_origins WHERE origin = 'https://default-enabled.vn'"))
                .isEqualTo("t");
    }

    @Test
    void uniqueToanHeThongVaCheckDangChuanHoa() throws Exception {
        exec(insertOrigin("https://unique.vn"));
        assertThatThrownBy(() -> exec(insertOrigin("https://unique.vn")))
                .hasMessageContaining("tenant_storefront_origins_origin_key");
        for (String bad : new String[]{"https://UPPER.vn", "https://x.vn/path", "ftp://x.vn", "x.vn", "https://x.vn:",
                "https://a b.vn", "https://x.vn?q=1", "https://*.vn"}) {
            assertThatThrownBy(() -> exec(insertOrigin(bad))).as(bad)
                    .hasMessageContaining("ck_tenant_storefront_origins_format");
        }
        exec(insertOrigin("https://ok.vn:8443"));
        exec(insertOrigin("http://localhost:5173"));
        // FK tenant
        assertThatThrownBy(() -> exec("INSERT INTO tenant_storefront_origins (id, tenant_id, origin) VALUES "
                + "(gen_random_uuid(), gen_random_uuid(), 'https://fk.vn')")).isInstanceOf(SQLException.class);
    }

    @Test
    void lichSuChiThemTriggerChanKeCaChuBangVaCheckAction() throws Exception {
        String id = "00000000-0000-0000-0000-00000000ab02";
        exec("INSERT INTO tenant_storefront_origin_history (id, tenant_id, origin_id, origin, action, actor_id) VALUES ('"
                + id + "', '" + TENANT + "', gen_random_uuid(), 'https://h.vn', 'ADDED', gen_random_uuid())");
        assertThatThrownBy(() -> exec("UPDATE tenant_storefront_origin_history SET origin = 'x' WHERE id = '" + id + "'"))
                .hasMessageContaining("chỉ thêm");
        assertThatThrownBy(() -> exec("DELETE FROM tenant_storefront_origin_history WHERE id = '" + id + "'"))
                .hasMessageContaining("chỉ thêm");
        assertThatThrownBy(() -> exec("INSERT INTO tenant_storefront_origin_history (id, tenant_id, origin_id, origin, "
                + "action, actor_id) VALUES (gen_random_uuid(), '" + TENANT + "', gen_random_uuid(), 'https://h.vn', "
                + "'DROPPED', gen_random_uuid())")).isInstanceOf(SQLException.class);
    }

    @Test
    void vaiTroUngDungKhongUpdateDeleteLichSuNhungConBangOriginVaGiuSauKhiChayLaiMigrate() throws Exception {
        String history = "tenant_storefront_origin_history";
        assertThat(one("SELECT has_table_privilege('identity_app', '" + history + "', 'INSERT')")).isEqualTo("t");
        assertThat(one("SELECT has_table_privilege('identity_app', '" + history + "', 'SELECT')")).isEqualTo("t");
        assertThat(one("SELECT has_table_privilege('identity_app', '" + history + "', 'UPDATE')")).isEqualTo("f");
        assertThat(one("SELECT has_table_privilege('identity_app', '" + history + "', 'DELETE')")).isEqualTo("f");
        for (String priv : new String[]{"INSERT", "UPDATE", "DELETE", "SELECT"}) {
            assertThat(one("SELECT has_table_privilege('identity_app', 'tenant_storefront_origins', '" + priv + "')"))
                    .as(priv).isEqualTo("t");
        }
        migrateAll(); // afterMigrate chạy lại GRANT ALL TABLES rồi REVOKE: quyền không quay lại
        assertThat(one("SELECT has_table_privilege('identity_app', '" + history + "', 'UPDATE')")).isEqualTo("f");
        assertThat(one("SELECT has_table_privilege('identity_app', '" + history + "', 'DELETE')")).isEqualTo("f");
    }
}

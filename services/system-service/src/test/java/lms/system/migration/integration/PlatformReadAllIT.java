package lms.system.migration.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-0008: policy platform_read_all (FOR SELECT) cộng với tenant_isolation. Cần Docker. Chạy bằng:
 * mvn test -Dtest=PlatformReadAllIT -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44.
 * Flyway chạy bằng chủ bảng (superuser của container); mọi kiểm tra hành vi chạy bằng vai trò NOSUPERUSER NOBYPASSRLS.
 */
@Testcontainers
class PlatformReadAllIT {

    private static final String A = "00000000-0000-0000-0000-0000000000d1";
    private static final String B = "00000000-0000-0000-0000-0000000000d2";

    private static final Map<String, List<String>> BANG = Map.of(
            "identity", List.of("roles", "departments"),
            "catalog", List.of("titles", "book_copies"),
            "circulation", List.of("borrowings", "borrowing_items", "fines", "reservations"),
            "audit", List.of("audit_logs"),
            "sales", List.of("sales_settings", "stock_items", "stock_ledger", "sales_orders", "sales_audit_log"));

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @BeforeAll
    static void migrate() throws Exception {
        for (String db : BANG.keySet()) {
            try (Connection c = admin("postgres"); Statement s = c.createStatement()) {
                s.execute("CREATE DATABASE " + db + "_db");
                s.execute("CREATE USER " + db + "_app WITH PASSWORD 'x' NOSUPERUSER NOBYPASSRLS");
            }
            Flyway.configure().dataSource(url(db + "_db"), postgres.getUsername(), postgres.getPassword())
                    .locations("classpath:db/migration/" + db).load().migrate();
            try (Connection c = admin(db + "_db"); Statement s = c.createStatement()) {
                s.execute("GRANT USAGE ON SCHEMA public TO " + db + "_app");
                s.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + db + "_app");
            }
        }
    }

    private static String url(String dbName) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/" + dbName;
    }

    private static Connection admin(String dbName) throws Exception {
        return DriverManager.getConnection(url(dbName), postgres.getUsername(), postgres.getPassword());
    }

    private static Connection app(String db) throws Exception {
        return DriverManager.getConnection(url(db + "_db"), db + "_app", "x");
    }

    private static void set(Statement s, String tenantId, String scope) throws Exception {
        s.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', false), set_config('app.tenant_scope', '"
                + scope + "', false)");
    }

    private static long count(Statement s, String from) throws Exception {
        try (ResultSet rs = s.executeQuery("SELECT count(*) FROM " + from)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void moiBangTrongDanhSachCoDungMotPolicyPlatformReadAllChiSelect() throws Exception {
        for (var e : BANG.entrySet()) {
            try (Connection c = admin(e.getKey() + "_db"); Statement s = c.createStatement()) {
                for (String table : e.getValue()) {
                    List<String> rows = new ArrayList<>();
                    try (ResultSet rs = s.executeQuery("SELECT cmd, permissive FROM pg_policies WHERE tablename = '"
                            + table + "' AND policyname = 'platform_read_all'")) {
                        while (rs.next()) rows.add(rs.getString(1) + "/" + rs.getString(2));
                    }
                    assertThat(rows).as(table).containsExactly("SELECT/PERMISSIVE");
                    assertThat(count(s, "pg_policies WHERE tablename = '" + table
                            + "' AND policyname = 'tenant_isolation'")).as(table + " giữ tenant_isolation")
                            .isEqualTo(1);
                }
            }
        }
    }

    @Test
    void identityRolesDepartmentsVaRolePermissions() throws Exception {
        try (Connection c = admin("identity_db"); Statement s = c.createStatement()) {
            s.execute("INSERT INTO tenants (id, code, name, status) VALUES ('" + B + "', 'b', 'B', 'ACTIVE') "
                    + "ON CONFLICT DO NOTHING");
            s.execute("INSERT INTO permissions (code, description) VALUES ('P/it', 'it') ON CONFLICT DO NOTHING");
            for (String[] t : new String[][] {{A, "a"}, {B, "b"}}) {
                s.execute("SELECT set_config('app.tenant_id', '" + t[0] + "', false)");
                s.execute("INSERT INTO roles (id, name, description, tenant_id) VALUES "
                        + "('00000000-0000-0000-0000-00000000" + t[1] + "a01', 'r" + t[1] + "', 'd', '" + t[0] + "')");
                s.execute("INSERT INTO departments (id, code, name, status, tenant_id) VALUES "
                        + "('00000000-0000-0000-0000-00000000" + t[1] + "a02', 'd" + t[1] + "', 'd', 'ACTIVE', '"
                        + t[0] + "')");
                s.execute("INSERT INTO role_permissions (role_id, permission_code) VALUES "
                        + "('00000000-0000-0000-0000-00000000" + t[1] + "a01', 'P/it')");
            }
        }
        String rp = "role_permissions WHERE permission_code = 'P/it'";
        try (Connection c = app("identity"); Statement s = c.createStatement()) {
            // (1) một tenant: như cũ
            set(s, A, "");
            assertThat(count(s, "roles WHERE name IN ('ra','rb')")).isEqualTo(1);
            assertThat(count(s, "departments WHERE code IN ('da','db')")).isEqualTo(1);
            assertThat(count(s, rp)).isEqualTo(1);

            // (2) scope all, không tenant: thấy mọi tenant; role_permissions kế thừa qua EXISTS trên roles
            set(s, "", "all");
            assertThat(count(s, "roles WHERE name IN ('ra','rb')")).isEqualTo(2);
            assertThat(count(s, "departments WHERE code IN ('da','db')")).isEqualTo(2);
            assertThat(count(s, rp)).isEqualTo(2);

            // (3) scope all: ghi không chạm dòng nào hoặc bị từ chối
            assertThat(s.executeUpdate("UPDATE roles SET description = 'x' WHERE name IN ('ra','rb')")).isZero();
            assertThat(s.executeUpdate("DELETE FROM departments WHERE code IN ('da','db')")).isZero();
            assertThat(s.executeUpdate("DELETE FROM role_permissions WHERE permission_code = 'P/it'")).isZero();
            assertThat(s.executeUpdate("UPDATE role_permissions SET permission_code = 'P/it' "
                    + "WHERE permission_code = 'P/it'")).isZero();
            assertThatThrownBy(() -> s.execute("INSERT INTO role_permissions (role_id, permission_code) VALUES "
                    + "('00000000-0000-0000-0000-00000000aa01', 'P/it2')")).hasMessageContaining("row-level security");
            assertThatThrownBy(() -> s.execute("INSERT INTO roles (id, name, description, tenant_id) VALUES "
                    + "('00000000-0000-0000-0000-00000000cc01', 'rc', 'd', '" + B + "')"))
                    .hasMessageContaining("row-level security");
            // đặt cả scope all lẫn tenant A: ghi vẫn chỉ chạm tenant A
            set(s, A, "all");
            assertThat(s.executeUpdate("UPDATE roles SET description = 'y' WHERE name IN ('ra','rb')"))
                    .isEqualTo(1);

            // (4) scope rỗng/sai và không tenant: không thấy gì
            set(s, "", "");
            assertThat(count(s, "roles")).isZero();
            assertThat(count(s, "departments")).isZero();
            set(s, "", "ALL");
            assertThat(count(s, "roles")).as("so khớp phân biệt hoa thường").isZero();
        }
        // (4b) kết nối mới chưa đặt biến nào (NULL): không mở
        try (Connection c = app("identity"); Statement s = c.createStatement()) {
            assertThat(count(s, "roles")).isZero();
        }
    }

    @Test
    void auditLogsDocXuyenTenantVoiScopeAll() throws Exception {
        String ins = "INSERT INTO audit_logs (id, event_id, event_type, aggregate_id, occurred_at, recorded_at, "
                + "payload, tenant_id) VALUES (gen_random_uuid(), gen_random_uuid(), 'T', 'agg', now(), now(), '{}', ";
        try (Connection c = admin("audit_db"); Statement s = c.createStatement()) {
            for (String t : List.of(A, B)) {
                s.execute("SELECT set_config('app.tenant_id', '" + t + "', false)");
                s.execute(ins + "'" + t + "')");
            }
        }
        try (Connection c = app("audit"); Statement s = c.createStatement()) {
            set(s, A, "");
            assertThat(count(s, "audit_logs")).isEqualTo(1);
            set(s, "", "all");
            assertThat(count(s, "audit_logs")).isEqualTo(2);
            assertThat(s.executeUpdate("UPDATE audit_logs SET payload = 'x'")).isZero();
            assertThat(s.executeUpdate("DELETE FROM audit_logs")).isZero();
            assertThatThrownBy(() -> s.execute(ins + "'" + A + "')")).hasMessageContaining("row-level security");
            set(s, "", "");
            assertThat(count(s, "audit_logs")).isZero();
        }
    }
}

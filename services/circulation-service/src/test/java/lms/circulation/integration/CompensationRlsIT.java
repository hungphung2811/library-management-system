package lms.circulation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * RLS của mọi bảng mới của đền bù (damage_levels, compensation_policies, fine_adjustment_requests, fine_refunds) bằng vai
 * trò app thật (circulation_app NOSUPERUSER NOBYPASSRLS, được cấp quyền bởi afterMigrate): fail-closed khi chưa đặt tenant,
 * tenant B không đọc/sửa/xóa/chèn thay tenant A, WITH CHECK chặn chèn sai tenant. Migration chạy bằng vai trò không bypass.
 * Chạy: -Dtest=CompensationRlsIT -DargLine=-Dapi.version=1.44 -Dsurefire.failIfNoSpecifiedTests=false
 */
@Testcontainers
class CompensationRlsIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static Connection su() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    static void su(String sql) throws SQLException {
        try (Connection c = su(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    static Connection app(UUID tenant) throws SQLException {
        Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "circulation_app", "circulation_app");
        if (tenant != null) {
            try (Statement s = c.createStatement()) {
                s.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
            }
        }
        return c;
    }

    @BeforeAll
    static void migrate() throws SQLException {
        su("CREATE ROLE mig_runner NOSUPERUSER NOBYPASSRLS");
        su("GRANT ALL ON SCHEMA public TO mig_runner");
        su("CREATE ROLE circulation_app LOGIN PASSWORD 'circulation_app' NOSUPERUSER NOBYPASSRLS");
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/circulation").initSql("SET ROLE mig_runner").load().migrate();
    }

    private static final String[] INSERTS = {
            "INSERT INTO damage_levels (id, tenant_id, code, label, rate, created_at, updated_at) VALUES ('%1$s', '%2$s', upper('L' || substr(replace('%1$s', '-', ''), 1, 8)), 'Nhẹ', 0.1, now(), now())",
            "INSERT INTO compensation_policies (id, tenant_id, lost_rate, price_source, found_window_days, updated_at) VALUES ('%1$s', '%2$s', 1.5, 'COVER_PRICE', 30, now())",
            "INSERT INTO fine_adjustment_requests (id, tenant_id, fine_id, reader_id, fine_reason, fine_amount, kind, payable_amount, reason, status, requested_by, requested_at) "
                    + "VALUES ('%1$s', '%2$s', gen_random_uuid(), gen_random_uuid(), 'LOST', 100000, 'WAIVE', 0, 'lý do', 'PENDING', gen_random_uuid(), now())",
            "INSERT INTO fine_refunds (id, tenant_id, fine_id, borrowing_id, book_copy_id, reader_id, lost_on, found_on, restore_status, refund_method, mode, "
                    + "fine_status_at_request, collected_amount, processing_fee, refund_amount, status, requested_by, requested_at) "
                    + "VALUES ('%1$s', '%2$s', gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), current_date, current_date, 'AVAILABLE', 'CASH', 'REFUND', "
                    + "'PAID', 100000, 0, 100000, 'PENDING', gen_random_uuid(), now())"};
    private static final List<String> TABLES = List.of("damage_levels", "compensation_policies", "fine_adjustment_requests",
            "fine_refunds");

    private static void insert(Connection c, int i, UUID id, UUID tenant) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(String.format(INSERTS[i], id, tenant));
        }
    }

    private static int count(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void moiBangMoiBatRlsForceVaCoChinhSachCaUsingLanWithCheck() throws Exception {
        try (Connection c = su(); Statement s = c.createStatement()) {
            for (String t : TABLES) {
                ResultSet rs = s.executeQuery("SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = '" + t + "'");
                assertThat(rs.next()).as(t).isTrue();
                assertThat(rs.getBoolean(1)).as(t + " ENABLE RLS").isTrue();
                assertThat(rs.getBoolean(2)).as(t + " FORCE RLS").isTrue();
                ResultSet p = s.executeQuery("SELECT qual, with_check FROM pg_policies WHERE tablename = '" + t + "'");
                assertThat(p.next()).as(t + " có policy").isTrue();
                assertThat(p.getString("qual")).contains("app.tenant_id");
                assertThat(p.getString("with_check")).as(t + " WITH CHECK").contains("app.tenant_id");
            }
        }
    }

    @Test
    void failClosedKhiChuaDatTenantVaKhongChenDuoc() throws Exception {
        UUID A = UUID.randomUUID();
        UUID B = UUID.randomUUID();
        try (Connection a = app(A)) {
            for (int i = 0; i < TABLES.size(); i++) insert(a, i, UUID.randomUUID(), A);
        }
        try (Connection none = app(null)) {
            for (int i = 0; i < TABLES.size(); i++) {
                assertThat(count(none, TABLES.get(i))).as(TABLES.get(i) + " không tenant thấy 0 dòng").isZero();
                int idx = i;
                assertThatThrownBy(() -> insert(none, idx, UUID.randomUUID(), A))
                        .as(TABLES.get(i) + " không tenant không chèn được").hasMessageContaining("row-level security");
            }
        }
    }

    @Test
    void tenantBKhongDocSuaXoaChenThayTenantA() throws Exception {
        UUID A = UUID.randomUUID();
        UUID B = UUID.randomUUID();
        UUID[] ids = {UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()};
        try (Connection a = app(A)) {
            for (int i = 0; i < TABLES.size(); i++) insert(a, i, ids[i], A);
        }
        try (Connection b = app(B); Statement s = b.createStatement()) {
            for (int i = 0; i < TABLES.size(); i++) {
                String t = TABLES.get(i);
                ResultSet rs = s.executeQuery("SELECT count(*) FROM " + t + " WHERE id = '" + ids[i] + "'");
                rs.next();
                assertThat(rs.getInt(1)).as(t + " B không thấy dòng của A").isZero();
                assertThat(s.executeUpdate("UPDATE " + t + " SET tenant_id = tenant_id WHERE id = '" + ids[i] + "'"))
                        .as(t + " B không sửa được").isZero();
                assertThat(s.executeUpdate("DELETE FROM " + t + " WHERE id = '" + ids[i] + "'"))
                        .as(t + " B không xóa được").isZero();
                int idx = i;
                // đang ở tenant B nhưng chèn dòng mang tenant A: WITH CHECK chặn
                assertThatThrownBy(() -> insert(b, idx, UUID.randomUUID(), A)).hasMessageContaining("row-level security");
            }
        }
        // tenant B dùng cùng khóa tự nhiên (code bậc hỏng, chính sách một dòng mỗi tenant) vẫn chèn được
        try (Connection b = app(B)) {
            insert(b, 0, UUID.randomUUID(), B);
            insert(b, 1, UUID.randomUUID(), B);
        }
        // dòng của A còn nguyên
        try (Connection a = app(A)) {
            for (int i = 0; i < TABLES.size(); i++) {
                try (Statement s = a.createStatement();
                     ResultSet rs = s.executeQuery("SELECT count(*) FROM " + TABLES.get(i) + " WHERE id = '" + ids[i] + "'")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
        }
    }

    @Test
    void rangBuocDbChanTuDuyetVaCapTienKhongHopLe() throws Exception {
        UUID A = UUID.randomUUID();
        UUID B = UUID.randomUUID();
        try (Connection a = app(A); Statement s = a.createStatement()) {
            UUID same = UUID.randomUUID();
            // decided_by = requested_by bị CHECK chặn (lớp bảo vệ thứ hai cho APPROVER_MUST_DIFFER)
            assertThatThrownBy(() -> s.execute("INSERT INTO fine_adjustment_requests (id, tenant_id, fine_id, reader_id, fine_reason, fine_amount, kind, "
                    + "payable_amount, reason, status, requested_by, requested_at, decided_by, decided_at) VALUES ('" + UUID.randomUUID() + "', '" + A
                    + "', gen_random_uuid(), gen_random_uuid(), 'LOST', 1000, 'WAIVE', 0, 'x', 'APPROVED', '" + same + "', now(), '" + same + "', now())"))
                    .hasMessageContaining("ck_fine_adjustment_approver_differs");
            // hai đề nghị PENDING cho cùng khoản: unique chặn
            UUID fine = UUID.randomUUID();
            String ins = "INSERT INTO fine_adjustment_requests (id, tenant_id, fine_id, reader_id, fine_reason, fine_amount, kind, payable_amount, reason, "
                    + "status, requested_by, requested_at) VALUES ('%s', '" + A + "', '" + fine + "', gen_random_uuid(), 'LOST', 1000, 'WAIVE', 0, 'x', 'PENDING', gen_random_uuid(), now())";
            s.execute(String.format(ins, UUID.randomUUID()));
            assertThatThrownBy(() -> s.execute(String.format(ins, UUID.randomUUID()))).hasMessageContaining("uq_fine_adjustment_pending");
            // rate 5.01 và minAmount > maxAmount bị CHECK chặn
            assertThatThrownBy(() -> s.execute("INSERT INTO damage_levels (id, tenant_id, code, label, rate, created_at, updated_at) VALUES ('"
                    + UUID.randomUUID() + "', '" + A + "', 'TOO_HIGH', 'x', 5.01, now(), now())")).hasMessageContaining("damage_levels");
        }
    }
}

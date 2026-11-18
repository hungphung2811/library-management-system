package lms.circulation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Migration V15 (gói D, ADR-0030): loan_reminders bật RLS + FORCE với policy USING và WITH CHECK, fail-closed khi chưa đặt
 * tenant, tenant B không đọc/sửa/xóa/chèn thay tenant A, ràng buộc kind/seq/khóa duy nhất/khóa ngoại kép; ba cột mới của
 * circulation_policies có mặc định 2/3/3 và CHECK theo khoảng. Chạy bằng vai trò app thật (circulation_app NOBYPASSRLS),
 * migration chạy bằng vai trò không bypass RLS. Cần Docker.
 * Chạy: -Dtest=LoanReminderRlsIT -DargLine=-Dapi.version=1.44 -Dsurefire.failIfNoSpecifiedTests=false
 */
@Testcontainers
class LoanReminderRlsIT {

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

    private static UUID borrowing(Connection c, UUID tenant) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Statement s = c.createStatement()) {
            s.execute("INSERT INTO borrowings (id, tenant_id, user_id, borrow_date, due_date) VALUES ('" + id + "', '"
                    + tenant + "', gen_random_uuid(), current_date - 10, current_date - 3)");
        }
        return id;
    }

    private static String reminder(UUID id, UUID tenant, UUID borrowing, String kind, int seq) {
        return "INSERT INTO loan_reminders (id, tenant_id, borrowing_id, kind, due_date, seq, sent_on, event_id) VALUES ('"
                + id + "', '" + tenant + "', '" + borrowing + "', '" + kind + "', current_date - 3, " + seq
                + ", current_date, gen_random_uuid())";
    }

    private static int count(Connection c, String where) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM loan_reminders WHERE " + where)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void bangBatRlsForceVaPolicyCoCaUsingLanWithCheck() throws Exception {
        try (Connection c = su(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = 'loan_reminders'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean(1)).as("ENABLE RLS").isTrue();
            assertThat(rs.getBoolean(2)).as("FORCE RLS").isTrue();
            ResultSet p = s.executeQuery("SELECT qual, with_check FROM pg_policies WHERE tablename = 'loan_reminders'");
            assertThat(p.next()).isTrue();
            assertThat(p.getString("qual")).contains("app.tenant_id");
            assertThat(p.getString("with_check")).contains("app.tenant_id");
        }
    }

    @Test
    void failClosedKhiChuaDatTenant() throws Exception {
        UUID a = UUID.randomUUID();
        try (Connection ca = app(a)) {
            UUID b = borrowing(ca, a);
            try (Statement s = ca.createStatement()) {
                s.execute(reminder(UUID.randomUUID(), a, b, "OVERDUE", 1));
            }
        }
        try (Connection none = app(null)) {
            assertThat(count(none, "true")).isZero();
            assertThatThrownBy(() -> {
                try (Statement s = none.createStatement()) {
                    s.execute(reminder(UUID.randomUUID(), a, UUID.randomUUID(), "OVERDUE", 1));
                }
            }).hasMessageContaining("row-level security");
        }
    }

    @Test
    void tenantBKhongDocSuaXoaChenThayTenantA() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        UUID borrowingA;
        try (Connection ca = app(a); Statement s = ca.createStatement()) {
            borrowingA = borrowing(ca, a);
            s.execute(reminder(id, a, borrowingA, "DUE_SOON", 1));
        }
        try (Connection cb = app(b); Statement s = cb.createStatement()) {
            assertThat(count(cb, "id = '" + id + "'")).as("B không thấy dòng của A").isZero();
            assertThat(s.executeUpdate("UPDATE loan_reminders SET seq = 9 WHERE id = '" + id + "'")).isZero();
            assertThat(s.executeUpdate("DELETE FROM loan_reminders WHERE id = '" + id + "'")).isZero();
            assertThatThrownBy(() -> s.execute(reminder(UUID.randomUUID(), a, borrowingA, "OVERDUE", 1)))
                    .hasMessageContaining("row-level security");
        }
        try (Connection ca = app(a)) {
            assertThat(count(ca, "id = '" + id + "'")).isEqualTo(1);
        }
    }

    @Test
    void rangBuocKindSeqKhoaDuyNhatVaKhoaNgoaiKep() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        try (Connection ca = app(a); Statement s = ca.createStatement()) {
            UUID p = borrowing(ca, a);
            s.execute(reminder(UUID.randomUUID(), a, p, "OVERDUE", 1));
            assertThatThrownBy(() -> s.execute(reminder(UUID.randomUUID(), a, p, "OVERDUE", 1)))
                    .hasMessageContaining("uq_loan_reminders_key");
            s.execute(reminder(UUID.randomUUID(), a, p, "OVERDUE", 2)); // lần nhắc kế tiếp hợp lệ
            s.execute(reminder(UUID.randomUUID(), a, p, "DUE_SOON", 1)); // loại khác cùng hạn hợp lệ
            assertThatThrownBy(() -> s.execute(reminder(UUID.randomUUID(), a, p, "OTHER", 1)))
                    .hasMessageContaining("loan_reminders_kind_check");
            assertThatThrownBy(() -> s.execute(reminder(UUID.randomUUID(), a, p, "OVERDUE", 0)))
                    .hasMessageContaining("loan_reminders_seq_check");
            // phiếu không tồn tại trong tenant: khóa ngoại kép chặn
            assertThatThrownBy(() -> s.execute(reminder(UUID.randomUUID(), a, UUID.randomUUID(), "OVERDUE", 1)))
                    .hasMessageContaining("fk_loan_reminders_borrowing_tenant");
        }
        // phiếu của A không thể gắn nhắc của B (FK kép theo tenant), và RLS chặn chèn sai tenant
        try (Connection cb = app(b); Statement s = cb.createStatement()) {
            UUID pb = borrowing(cb, b);
            s.execute(reminder(UUID.randomUUID(), b, pb, "OVERDUE", 1));
            assertThat(count(cb, "tenant_id = '" + b + "'")).isEqualTo(1);
        }
    }

    @Test
    void xoaPhieuXoaCacLanNhacCuaNo() throws Exception {
        UUID a = UUID.randomUUID();
        try (Connection ca = app(a); Statement s = ca.createStatement()) {
            UUID p = borrowing(ca, a);
            s.execute(reminder(UUID.randomUUID(), a, p, "OVERDUE", 1));
            s.execute("DELETE FROM borrowings WHERE id = '" + p + "'");
            assertThat(count(ca, "borrowing_id = '" + p + "'")).isZero();
        }
    }

    @Test
    void baCotNhacHanCuaChinhSachCoMacDinhVaCheck() throws Exception {
        UUID a = UUID.randomUUID();
        try (Connection ca = app(a); Statement s = ca.createStatement()) {
            // chèn theo cột cũ của V8: ba cột mới nhận mặc định 2 / 3 / 3
            s.execute("INSERT INTO circulation_policies (id, tenant_id, version, loan_days, max_renewals, renewal_days, "
                    + "max_active_loans_per_reader, fine_per_day, grace_days, reservation_hold_days, updated_at) VALUES ('"
                    + UUID.randomUUID() + "', '" + a + "', 0, 14, 2, 14, 5, 5000, 0, 3, now())");
            ResultSet rs = s.executeQuery("SELECT due_reminder_days_before, overdue_reminder_interval_days, "
                    + "overdue_reminder_max FROM circulation_policies");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(2);
            assertThat(rs.getInt(2)).isEqualTo(3);
            assertThat(rs.getInt(3)).isEqualTo(3);
            assertThatThrownBy(() -> s.execute("UPDATE circulation_policies SET due_reminder_days_before = 31"))
                    .hasMessageContaining("due_reminder_days_before");
            assertThatThrownBy(() -> s.execute("UPDATE circulation_policies SET overdue_reminder_interval_days = 0"))
                    .hasMessageContaining("overdue_reminder_interval_days");
            assertThatThrownBy(() -> s.execute("UPDATE circulation_policies SET overdue_reminder_max = 21"))
                    .hasMessageContaining("overdue_reminder_max");
        }
    }
}

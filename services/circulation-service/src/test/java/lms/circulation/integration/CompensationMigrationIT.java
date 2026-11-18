package lms.circulation.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Tiêu chí 23: migration V11/V12 chạy trên DB đã có dữ liệu V10 mà không mất dữ liệu. Cần Docker. Chạy:
 * mvn test -Dtest=CompensationMigrationIT. Chỉ dùng Flyway + JDBC (superuser), không khởi động Spring.
 */
@Testcontainers
class CompensationMigrationIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/circulation").target(target).load();
    }

    @Test
    void duLieuCuVanNguyenSauKhiMigrateLenV12() throws Exception {
        String tenant = "00000000-0000-0000-0000-0000000000d1";
        UUID borrowing = UUID.randomUUID();
        UUID fine = UUID.randomUUID();
        UUID copy = UUID.randomUUID();
        flyway("10").migrate();
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            s.execute("INSERT INTO borrowings (id, tenant_id, user_id, borrow_date, due_date, renew_count, loan_days) VALUES ('"
                    + borrowing + "', '" + tenant + "', gen_random_uuid(), current_date, current_date + 14, 0, 14)");
            s.execute("INSERT INTO borrowing_items (borrowing_id, tenant_id, book_copy_id) VALUES ('" + borrowing + "', '" + tenant + "', '" + copy + "')");
            s.execute("INSERT INTO fines (id, tenant_id, reader_id, borrowing_id, book_copy_id, reason, amount, status, created_at) VALUES ('"
                    + fine + "', '" + tenant + "', gen_random_uuid(), '" + borrowing + "', '" + copy + "', 'LOST', 150000, 'UNPAID', now())");
        }

        flyway("12").migrate();

        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            ResultSet item = s.executeQuery("SELECT condition_grade, condition_note, condition_recorded FROM borrowing_items WHERE borrowing_id = '" + borrowing + "'");
            assertThat(item.next()).isTrue();
            assertThat(item.getString("condition_grade")).isNull();
            assertThat(item.getBoolean("condition_recorded")).isFalse();
            ResultSet f = s.executeQuery("SELECT amount, payable_amount, status, damage_level_code, evidence_urls FROM fines WHERE id = '" + fine + "'");
            assertThat(f.next()).isTrue();
            assertThat(f.getBigDecimal("amount")).isEqualByComparingTo("150000");
            assertThat(f.getBigDecimal("payable_amount")).isNull(); // NULL = bằng amount
            assertThat(f.getString("status")).isEqualTo("UNPAID");
            assertThat(f.getString("damage_level_code")).isNull();
            // trạng thái mới được phép, ràng buộc cũ ck_fines_paid vẫn còn
            s.execute("UPDATE fines SET status = 'REDUCED', payable_amount = 100000 WHERE id = '" + fine + "'");
            ResultSet rls = s.executeQuery("SELECT count(*) FROM pg_class WHERE relname IN ('damage_levels','compensation_policies',"
                    + "'fine_adjustment_requests','fine_refunds') AND relrowsecurity AND relforcerowsecurity");
            rls.next();
            assertThat(rls.getInt(1)).isEqualTo(4);
        }
    }
}

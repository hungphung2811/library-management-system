package lms.sales.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Sales V14 (stock_items.work_id) trên Postgres thật bằng vai trò KHÔNG bypass RLS: backfill md5('lms-work:'||title_id)
 * cho mọi tenant khớp công thức Java (catalog V10), không đổi work_id đã có, chạy lại không nhân đôi, index nhóm theo tác phẩm.
 * Chạy: -Dtest=StockWorkMigrationIT -DargLine=-Dapi.version=1.44 -Dsurefire.failIfNoSpecifiedTests=false
 */
@Testcontainers
class StockWorkMigrationIT {

    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000ee01");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000ee02");
    static final UUID T1 = UUID.fromString("00000000-0000-0000-0000-0000000a0001");
    static final UUID T2 = UUID.fromString("00000000-0000-0000-0000-0000000a0002");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static Flyway flyway(String target) {
        var cfg = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/sales").initSql("SET ROLE mig_runner");
        if (target != null) cfg.target(target);
        return cfg.load();
    }

    static Connection su() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    static void exec(String sql) throws SQLException {
        try (Connection c = su(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    static List<String> col(String sql) throws SQLException {
        try (Connection c = su(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getString(1));
            return out;
        }
    }

    static UUID md5Work(UUID titleId) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(("lms-work:" + titleId).getBytes(StandardCharsets.UTF_8));
        long hi = 0, lo = 0;
        for (int i = 0; i < 8; i++) hi = (hi << 8) | (d[i] & 0xff);
        for (int i = 8; i < 16; i++) lo = (lo << 8) | (d[i] & 0xff);
        return new UUID(hi, lo);
    }

    static void stock(UUID tenant, UUID title) throws SQLException {
        exec("INSERT INTO stock_items (id, tenant_id, title_id, on_hand, active) VALUES ('" + UUID.randomUUID() + "', '"
                + tenant + "', '" + title + "', 5, true)");
    }

    @BeforeAll
    static void migrate() throws SQLException {
        exec("CREATE ROLE mig_runner NOSUPERUSER NOBYPASSRLS");
        exec("GRANT ALL ON SCHEMA public TO mig_runner");
        flyway("13").migrate();
        stock(A, T1);
        stock(A, T2);
        stock(B, T1);
        flyway(null).migrate();
    }

    @Test
    void backfillMoiTenantKhopCongThucJava() throws Exception {
        assertThat(col("SELECT count(*) FROM stock_items WHERE work_id IS NULL")).containsExactly("0");
        assertThat(col("SELECT DISTINCT work_id::text FROM stock_items WHERE title_id = '" + T1 + "'"))
                .containsExactly(md5Work(T1).toString());
        assertThat(col("SELECT DISTINCT work_id::text FROM stock_items WHERE title_id = '" + T2 + "'"))
                .containsExactly(md5Work(T2).toString());
    }

    @Test
    void chayLaiKhongDoiVaIndexNhomTheoTacPhamCoMat() throws Exception {
        flyway(null).migrate();
        assertThat(col("SELECT count(*) FROM flyway_schema_history WHERE version = '14' AND success")).containsExactly("1");
        assertThat(col("SELECT indexname FROM pg_indexes WHERE tablename = 'stock_items' AND indexname = 'idx_stock_items_work'"))
                .containsExactly("idx_stock_items_work");
        // ứng dụng ghi work_id khác md5 (ấn bản đã gắn tác phẩm thật) thì Flyway chạy lại không đè
        UUID t3 = UUID.randomUUID();
        UUID custom = UUID.randomUUID();
        stock(A, t3);
        exec("UPDATE stock_items SET work_id = '" + custom + "' WHERE title_id = '" + t3 + "'");
        flyway(null).migrate();
        assertThat(col("SELECT work_id::text FROM stock_items WHERE title_id = '" + t3 + "'")).containsExactly(custom.toString());
    }
}

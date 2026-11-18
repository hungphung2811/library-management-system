package lms.circulation.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * V13 (reservations.work_id, language) trên Postgres THẬT: backfill chạy bằng vai trò KHÔNG phải superuser và không bypass
 * RLS (giống system-service thật: chủ bảng chịu FORCE RLS), nhiều tenant, công thức md5 khớp Java, chạy lại không đổi,
 * unique index "một độc giả một đặt trước hiệu lực trên một tác phẩm" và index hàng đợi theo tác phẩm.
 *
 * <p>BUG-3 (đang đỏ 3/4 ca): bảng reservations CHỈ có policy tenant_isolation (V6), không có platform_read_all như
 * borrowings/fines (V7), nên khối DO của V13 đặt app.tenant_scope='all' vẫn SELECT DISTINCT tenant_id ra 0 dòng khi chạy bằng
 * chủ bảng thường (vai trò {@code circulation} trong init-databases.sql, chịu FORCE RLS): backfill không cập nhật dòng nào,
 * mọi đặt trước cũ giữ work_id NULL. Chạy bằng superuser thì bypass RLS nên không lộ.
 *
 * <p>Chạy: mvn -pl services/circulation-service -am test -Dtest=ReservationWorkMigrationIT -DargLine=-Dapi.version=1.44
 * -Dsurefire.failIfNoSpecifiedTests=false
 */
@Testcontainers
class ReservationWorkMigrationIT {

    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000ee01");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000ee02");
    static final UUID TITLE_1 = UUID.fromString("00000000-0000-0000-0000-0000000a0001");
    static final UUID TITLE_2 = UUID.fromString("00000000-0000-0000-0000-0000000a0002");
    static final UUID READER_1 = UUID.fromString("00000000-0000-0000-0000-0000000c0001");
    static final UUID READER_2 = UUID.fromString("00000000-0000-0000-0000-0000000c0002");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static Flyway flyway(String target) {
        var cfg = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/circulation").initSql("SET ROLE mig_runner");
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

    static void reservation(UUID tenant, UUID id, UUID reader, UUID title, String status) throws SQLException {
        exec("INSERT INTO reservations (id, tenant_id, reader_id, title_id, reserved_at, status) VALUES ('" + id + "', '"
                + tenant + "', '" + reader + "', '" + title + "', now(), '" + status + "')");
    }

    static UUID md5Work(UUID titleId) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(("lms-work:" + titleId).getBytes(StandardCharsets.UTF_8));
        long hi = 0, lo = 0;
        for (int i = 0; i < 8; i++) hi = (hi << 8) | (d[i] & 0xff);
        for (int i = 8; i < 16; i++) lo = (lo << 8) | (d[i] & 0xff);
        return new UUID(hi, lo);
    }

    static final UUID R_A1 = UUID.fromString("00000000-0000-0000-0000-0000000d0001");
    static final UUID R_A2 = UUID.fromString("00000000-0000-0000-0000-0000000d0002");
    static final UUID R_A3 = UUID.fromString("00000000-0000-0000-0000-0000000d0003");
    static final UUID R_B1 = UUID.fromString("00000000-0000-0000-0000-0000000d0004");

    @BeforeAll
    static void migrate() throws SQLException {
        exec("CREATE ROLE mig_runner NOSUPERUSER NOBYPASSRLS");
        exec("GRANT ALL ON SCHEMA public TO mig_runner");
        flyway("12").migrate();
        reservation(A, R_A1, READER_1, TITLE_1, "WAITING");
        reservation(A, R_A2, READER_2, TITLE_1, "WAITING");
        reservation(A, R_A3, READER_1, TITLE_2, "CANCELLED");
        reservation(B, R_B1, READER_1, TITLE_1, "WAITING"); // cùng reader/title nhưng khác tenant
        flyway(null).migrate(); // V13 dưới vai trò không bypass RLS
    }

    private static List<String> col(String sql) throws SQLException {
        try (Connection c = su(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getString(1));
            return out;
        }
    }

    @Test
    void backfillDienWorkIdTatDinhChoMoiTenantVaKhopCongThucJava() throws Exception {
        assertThat(col("SELECT count(*) FROM reservations WHERE work_id IS NULL")).containsExactly("0");
        assertThat(col("SELECT work_id FROM reservations WHERE id = '" + R_A1 + "'"))
                .containsExactly(md5Work(TITLE_1).toString());
        assertThat(col("SELECT work_id FROM reservations WHERE id = '" + R_B1 + "'"))
                .containsExactly(md5Work(TITLE_1).toString());
        assertThat(col("SELECT work_id FROM reservations WHERE id = '" + R_A3 + "'"))
                .containsExactly(md5Work(TITLE_2).toString());
        // chỉ xét các dòng đã seed trước migrate: test khác trong lớp (uniqueIndex...) chèn dòng có language vào cùng DB
        assertThat(col("SELECT count(*) FROM reservations WHERE language IS NOT NULL AND id IN ('" + R_A1 + "','" + R_A2
                + "','" + R_A3 + "','" + R_B1 + "')")).containsExactly("0");
    }

    @Test
    void chayLaiKhoiBackfillKhongDoiDuLieuVaFlywayKhongChayLaiV13() throws Exception {
        flyway(null).migrate(); // no-op
        assertThat(col("SELECT count(*) FROM flyway_schema_history WHERE version = '13' AND success")).containsExactly("1");
        assertThat(col("SELECT count(*) FROM reservations WHERE id IN ('" + R_A1 + "','" + R_A2 + "','" + R_A3 + "','" + R_B1
                + "') AND work_id IS NOT NULL")).containsExactly("4");
    }

    @Test
    void uniqueIndexMotDocGiaMotDatTruocHieuLucTrenMotTacPhamTheoTenant() throws Exception {
        UUID work = UUID.randomUUID();
        UUID r1 = UUID.randomUUID();
        UUID reader = UUID.randomUUID();
        UUID titleX = UUID.randomUUID();
        UUID titleY = UUID.randomUUID();
        exec("INSERT INTO reservations (id, tenant_id, reader_id, title_id, reserved_at, status, work_id, language) VALUES ('"
                + r1 + "', '" + A + "', '" + reader + "', '" + titleX + "', now(), 'WAITING', '" + work + "', 'vi')");
        // cùng độc giả, ấn bản KHÁC (title khác) nhưng cùng tác phẩm: bị chặn bởi uq_reservations_active_work
        assertThatThrownBy(() -> exec("INSERT INTO reservations (id, tenant_id, reader_id, title_id, reserved_at, status, "
                + "work_id, language) VALUES ('" + UUID.randomUUID() + "', '" + A + "', '" + reader + "', '" + titleY
                + "', now(), 'WAITING', '" + work + "', 'en')"))
                .hasMessageContaining("uq_reservations_active_work");
        // khác tenant thì được
        exec("INSERT INTO reservations (id, tenant_id, reader_id, title_id, reserved_at, status, work_id) VALUES ('"
                + UUID.randomUUID() + "', '" + B + "', '" + reader + "', '" + titleY + "', now(), 'WAITING', '" + work + "')");
        // đặt trước cũ đã hủy rồi đặt lại thì được
        exec("UPDATE reservations SET status = 'CANCELLED' WHERE id = '" + r1 + "'");
        exec("INSERT INTO reservations (id, tenant_id, reader_id, title_id, reserved_at, status, work_id) VALUES ('"
                + UUID.randomUUID() + "', '" + A + "', '" + reader + "', '" + titleY + "', now(), 'WAITING', '" + work + "')");
    }

    @Test
    void indexHangDoiTheoTacPhamPhuDungTruyVanFifoVaRlsChanCheoTenant() throws Exception {
        List<String> idx = col("SELECT indexname FROM pg_indexes WHERE tablename = 'reservations' ORDER BY 1");
        assertThat(idx).contains("idx_reservations_work_queue", "uq_reservations_active_work",
                "idx_reservations_title_active");
        // vai trò app (không bypass RLS): không đặt tenant thì không thấy gì, đặt A thì chỉ thấy của A
        exec("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'circulation_app') THEN "
                + "CREATE ROLE circulation_app LOGIN PASSWORD 'circulation_app' NOSUPERUSER NOBYPASSRLS; END IF; END $$");
        exec("GRANT SELECT ON reservations TO circulation_app");
        exec("GRANT USAGE ON SCHEMA public TO circulation_app");
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), "circulation_app", "circulation_app");
             Statement s = c.createStatement()) {
            ResultSet none = s.executeQuery("SELECT count(*) FROM reservations");
            none.next();
            assertThat(none.getInt(1)).as("fail-closed khi chưa đặt app.tenant_id").isZero();
            s.execute("SELECT set_config('app.tenant_id', '" + A + "', false)");
            ResultSet a = s.executeQuery("SELECT count(*) FROM reservations WHERE work_id = '" + md5Work(TITLE_1) + "'");
            a.next();
            assertThat(a.getInt(1)).isEqualTo(2); // R_A1, R_A2; R_B1 của tenant B bị RLS ẩn
        }
    }
}

package lms.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lms.catalog.catalog.domain.LegacyIds;
import lms.catalog.catalog.domain.Reference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cần Docker. Migration V10 (ADR-0026 E1) trên Postgres thật: chạy V1-V9 bằng vai trò KHÔNG phải superuser (chủ bảng chịu
 * FORCE RLS như thật), seed 2 tenant bằng superuser, rồi chạy V10 cũng bằng vai trò đó. Kiểm: backfill dưới RLS nhiều tenant
 * (mỗi đầu sách đúng một work, id tất định, work_authors chép đúng, series theo tenant), name_key của series khớp
 * {@link Reference#keyOf} của Java, chạy lại khối backfill không nhân đôi, và RLS của bảng mới cô lập tenant.
 * Chạy: mvn -pl services/catalog-service -am test -Dtest=CatalogWorksMigrationIT -DargLine=-Dapi.version=1.44
 * -Dsurefire.failIfNoSpecifiedTests=false
 */
@Testcontainers
class CatalogWorksMigrationIT {

    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000ee01");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000ee02");
    static final UUID TA1 = UUID.fromString("00000000-0000-0000-0000-0000000a0001");
    static final UUID TA2 = UUID.fromString("00000000-0000-0000-0000-0000000a0002");
    static final UUID TA3 = UUID.fromString("00000000-0000-0000-0000-0000000a0003");
    static final UUID TB1 = UUID.fromString("00000000-0000-0000-0000-0000000b0001");
    static final UUID TB2 = UUID.fromString("00000000-0000-0000-0000-0000000b0002");
    static final UUID AUTHOR_A = UUID.fromString("00000000-0000-0000-0000-0000000aa001");

    /** Chuỗi series thô (có dấu tiếng Việt, khoảng trắng kép, hoa thường, tab) mà SQL phải chuẩn hóa như Java. */
    static final String[] RAW_SERIES = {"  Harry   Potter ", "harry potter", "Đắc  Nhân\tTâm", "ĐẮC NHÂN TÂM", "Chuyện Kể  Xưa"};

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static Flyway flyway(String target) {
        var cfg = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/catalog").initSql("SET ROLE mig_runner");
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

    static String lit(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    static void title(UUID tenant, UUID id, String name, String author, String isbn, String series, Integer seriesNo)
            throws SQLException {
        exec("INSERT INTO titles (id, name, author, isbn, cover_price, tenant_id, series, series_number, description) VALUES ('"
                + id + "', " + lit(name) + ", " + lit(author) + ", " + (isbn == null ? "NULL" : lit(isbn)) + ", 1000, '"
                + tenant + "', " + (series == null ? "NULL" : lit(series)) + ", " + (seriesNo == null ? "NULL" : seriesNo)
                + ", 'Mô tả " + name + "')");
    }

    @BeforeAll
    static void migrate() throws SQLException {
        exec("CREATE ROLE mig_runner NOSUPERUSER NOBYPASSRLS");
        exec("GRANT ALL ON SCHEMA public TO mig_runner");
        flyway("9").migrate();
        // authors (V5) cho work_authors
        exec("INSERT INTO authors (id, tenant_id, name, name_key) VALUES ('" + AUTHOR_A + "', '" + A + "', 'Rowling', 'rowling')");
        title(A, TA1, "Harry Potter 1", "Rowling", "111", RAW_SERIES[0], 1);
        title(A, TA2, "Harry Potter 2", "Rowling", "112", RAW_SERIES[1], 2);
        title(A, TA3, "Sách lẻ", "Vô danh", null, null, null);
        title(B, TB1, "Đắc nhân tâm", "Carnegie", "211", RAW_SERIES[2], 1);
        title(B, TB2, "Đắc nhân tâm 2", "Carnegie", "212", RAW_SERIES[3], 2);
        exec("INSERT INTO title_authors (title_id, author_id, tenant_id, sort_order) VALUES ('" + TA1 + "', '" + AUTHOR_A
                + "', '" + A + "', 0)");
        flyway(null).migrate(); // V10 dưới vai trò không bypass RLS
    }

    private static List<String> col(String sql) throws SQLException {
        try (Connection c = su(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getString(1));
            return out;
        }
    }

    @Test
    void moiDauSachCoDungMotWorkTatDinhVaTitlesDuocGan() throws Exception {
        assertThat(col("SELECT count(*) FROM titles WHERE work_id IS NULL")).containsExactly("0");
        assertThat(col("SELECT count(*) FROM works")).containsExactly("5");
        for (UUID t : List.of(TA1, TA2, TA3, TB1, TB2)) {
            UUID expected = LegacyIds.workIdOfTitle(t);
            assertThat(col("SELECT work_id FROM titles WHERE id = '" + t + "'")).containsExactly(expected.toString());
            assertThat(col("SELECT md5('lms-work:' || '" + t + "')::uuid")).containsExactly(expected.toString());
        }
        // work thuộc đúng tenant của đầu sách (không lệch tenant dù đặt app.tenant_id đổi giữa vòng lặp)
        assertThat(col("SELECT count(*) FROM titles t JOIN works w ON w.id = t.work_id AND w.tenant_id = t.tenant_id"))
                .containsExactly("5");
        assertThat(col("SELECT count(*) FROM works WHERE tenant_id = '" + A + "'")).containsExactly("3");
        assertThat(col("SELECT count(*) FROM works WHERE tenant_id = '" + B + "'")).containsExactly("2");
        assertThat(col("SELECT title FROM works WHERE id = '" + LegacyIds.workIdOfTitle(TA3) + "'"))
                .containsExactly("Sách lẻ");
        assertThat(col("SELECT kind FROM works GROUP BY kind")).containsExactly("SINGLE");
    }

    @Test
    void workAuthorsChepTuTitleAuthors() throws Exception {
        assertThat(col("SELECT work_id || ':' || author_id || ':' || tenant_id FROM work_authors"))
                .containsExactly(LegacyIds.workIdOfTitle(TA1) + ":" + AUTHOR_A + ":" + A);
    }

    @Test
    void seriesTheoTenantVaNameKeyKhopJava() throws Exception {
        try (Connection c = su(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, tenant_id, name, name_key FROM series ORDER BY tenant_id, name_key")) {
            int n = 0;
            while (rs.next()) {
                n++;
                String name = rs.getString("name");
                String key = rs.getString("name_key");
                assertThat(key).isEqualTo(Reference.keyOf(name));
                assertThat(name).isEqualTo(Reference.normalizeName(name));
                assertThat(UUID.fromString(rs.getString("id")))
                        .isEqualTo(LegacyIds.seriesId(UUID.fromString(rs.getString("tenant_id")), key));
            }
            assertThat(n).isEqualTo(2); // A: "harry potter" (2 chuỗi thô gộp một), B: "đắc nhân tâm" (2 chuỗi thô gộp một)
        }
        // Mọi chuỗi thô, qua Java, ra đúng khóa của series đã backfill cho tenant của nó
        for (String raw : List.of(RAW_SERIES[0], RAW_SERIES[1])) {
            assertThat(col("SELECT count(*) FROM series WHERE tenant_id = '" + A + "' AND name_key = " + lit(Reference.keyOf(raw))))
                    .containsExactly("1");
        }
        for (String raw : List.of(RAW_SERIES[2], RAW_SERIES[3])) {
            assertThat(col("SELECT count(*) FROM series WHERE tenant_id = '" + B + "' AND name_key = " + lit(Reference.keyOf(raw))))
                    .containsExactly("1");
        }
        // work gắn đúng series và số tập; sách lẻ không có series
        assertThat(col("SELECT series_id || ':' || series_number FROM works WHERE id = '" + LegacyIds.workIdOfTitle(TA2) + "'"))
                .containsExactly(LegacyIds.seriesId(A, Reference.keyOf(RAW_SERIES[1])) + ":2");
        assertThat(col("SELECT count(*) FROM works WHERE id = '" + LegacyIds.workIdOfTitle(TA3) + "' AND series_id IS NULL"))
                .containsExactly("1");
    }

    @Test
    void chayLaiKhoiBackfillKhongNhanDoi() throws Exception {
        String sql = Files.readString(Path.of("..", "system-service", "src", "main", "resources", "db", "migration",
                "catalog", "V10__works_series_expand.sql"), StandardCharsets.UTF_8);
        String block = sql.substring(sql.lastIndexOf("DO $$"));
        try (Connection c = su()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET ROLE mig_runner");
                s.execute(block);
            }
            c.commit();
        }
        assertThat(col("SELECT count(*) FROM works")).containsExactly("5");
        assertThat(col("SELECT count(*) FROM series")).containsExactly("2");
        assertThat(col("SELECT count(*) FROM work_authors")).containsExactly("1");
    }

    @Test
    void rlsCoLapTenantTrenBangMoiVaFailClosed() throws Exception {
        try (Connection c = su(); Statement s = c.createStatement()) {
            s.execute("SET ROLE mig_runner");
            assertThat(count(s, "works")).isZero(); // chưa đặt app.tenant_id: không thấy dòng nào
            s.execute("SELECT set_config('app.tenant_id', '" + A + "', false)");
            assertThat(count(s, "works")).isEqualTo(3);
            assertThat(count(s, "series")).isEqualTo(1);
            s.execute("SELECT set_config('app.tenant_id', '" + B + "', false)");
            assertThat(count(s, "works")).isEqualTo(2);
            s.execute("SELECT set_config('app.tenant_id', '', false)");
            s.execute("SELECT set_config('app.tenant_scope', 'all', false)");
            assertThat(count(s, "works")).isEqualTo(5); // platform_read_all
        }
    }

    private static int count(Statement s, String table) throws SQLException {
        try (ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}

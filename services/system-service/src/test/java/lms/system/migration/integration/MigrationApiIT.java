package lms.system.migration.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cần Docker. Chạy bằng: mvn test -Dtest=MigrationApiIT -Dsurefire.failIfNoSpecifiedTests=false.
 * Một container PostgreSQL chứa 4 DB; migration chạy lúc khởi động context (profile dev tắt security).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationApiIT {

    private static final List<String> DBS = List.of("identity", "catalog", "circulation", "audit", "system");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    /** Thư mục script bổ sung cho catalog, ban đầu rỗng. */
    static Path extraScripts;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        for (String db : DBS) {
            try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                    postgres.getPassword()); Statement s = c.createStatement()) {
                s.execute("CREATE DATABASE " + db + "_db");
                // Vai trò chạy ứng dụng như init-databases.sql: callback afterMigrate.sql cấp quyền cho nó.
                // audit_app cố ý tạo muộn (test vaiTroTaoMuon...) để mô phỏng volume cũ.
                if (!LATE_ROLE_DB.equals(db)) taoVaiTroApp(s, db);
            }
            String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/" + db
                    + "_db";
            // Context menu dùng system_app trên system_db (spring.datasource); migration chạy sau bằng chủ bảng
            if ("system".equals(db)) {
                r.add("spring.datasource.url", () -> url);
                r.add("spring.datasource.username", () -> "system_app");
                r.add("spring.datasource.password", () -> "system_app");
            }
            r.add("migration.targets." + db + ".url", () -> url);
            r.add("migration.targets." + db + ".username", postgres::getUsername);
            r.add("migration.targets." + db + ".password", postgres::getPassword);
        }
        extraScripts = Files.createTempDirectory("lms-extra-migrations");
        r.add("migration.targets.catalog.locations[0]", () -> "classpath:db/migration/catalog");
        r.add("migration.targets.catalog.locations[1]", () -> "filesystem:" + extraScripts.toAbsolutePath());
    }

    @Autowired
    MockMvc mvc;

    private static final String LATE_ROLE_DB = "audit";

    private static void taoVaiTroApp(Statement s, String db) throws Exception {
        s.execute("CREATE USER " + db + "_app WITH PASSWORD '" + db + "_app' NOSUPERUSER NOBYPASSRLS");
    }

    private static int demScript(String target) throws Exception {
        return new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/" + target + "/V*.sql").length;
    }

    /** Vai trò có đủ SELECT/INSERT/UPDATE/DELETE trên bảng hay không (hỏi bằng superuser). */
    private static boolean coDml(String db, String role, String table) throws Exception {
        try (Connection c = connect(db, postgres.getUsername(), postgres.getPassword());
                Statement s = c.createStatement();
                var rs = s.executeQuery("SELECT has_table_privilege('" + role + "', '" + table
                        + "', 'SELECT,INSERT,UPDATE,DELETE')")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private static Connection connect(String db, String user, String password) throws Exception {
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/" + db + "_db";
        return DriverManager.getConnection(url, user, password);
    }

    @Test
    @Order(1)
    void khoiDongDaChayDuScriptVaLanHaiKhongChayLai() throws Exception {
        // Số script đếm từ classpath để test không vỡ mỗi khi có người thêm migration mới.
        int identity = demScript("identity");
        int catalog = demScript("catalog");
        int circulation = demScript("circulation");
        int audit = demScript("audit");
        int system = demScript("system");
        mvc.perform(get("/internal/migrations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(identity + catalog + circulation + audit + system))
                .andExpect(jsonPath("$.pending").value(0))
                .andExpect(jsonPath("$.targets[?(@.target=='identity')].applied").value(identity))
                .andExpect(jsonPath("$.targets[?(@.target=='catalog')].applied").value(catalog))
                .andExpect(jsonPath("$.targets[?(@.target=='circulation')].applied").value(circulation))
                .andExpect(jsonPath("$.targets[?(@.target=='audit')].applied").value(audit))
                .andExpect(jsonPath("$.targets[?(@.target=='system')].applied").value(system));

        mvc.perform(get("/internal/migrations/identity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scripts.length()").value(identity))
                .andExpect(jsonPath("$.scripts[0].version").value("1"))
                .andExpect(jsonPath("$.scripts[0].state").value("APPLIED"))
                .andExpect(jsonPath("$.scripts[0].checksum").isNumber())
                .andExpect(jsonPath("$.scripts[0].installedBy").isNotEmpty());

        mvc.perform(post("/internal/migrations/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executed").value(0));
        mvc.perform(post("/internal/migrations/identity/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executed").value(0));
    }

    @Test
    @Order(2)
    void scriptMoiBaoPendingRoiChayXongBaoApplied() throws Exception {
        // V999 để không trùng số với migration catalog thật về sau
        Files.writeString(extraScripts.resolve("V999__them_cot_gia_lap.sql"),
                "ALTER TABLE titles ADD COLUMN IF NOT EXISTS ghi_chu_migration VARCHAR(50);");
        int catalog = demScript("catalog");

        mvc.perform(get("/internal/migrations/catalog"))
                .andExpect(jsonPath("$.applied").value(catalog))
                .andExpect(jsonPath("$.pending").value(1))
                .andExpect(jsonPath("$.scripts[" + catalog + "].version").value("999"))
                .andExpect(jsonPath("$.scripts[" + catalog + "].state").value("PENDING"));

        mvc.perform(post("/internal/migrations/catalog/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executed").value(1));

        mvc.perform(get("/internal/migrations/catalog"))
                .andExpect(jsonPath("$.applied").value(catalog + 1))
                .andExpect(jsonPath("$.pending").value(0))
                .andExpect(jsonPath("$.scripts[" + catalog + "].state").value("APPLIED"));
    }

    @Test
    @Order(3)
    void vaiTroTaoMuonSauKhiDaMigrateThiChayLaiMigrationLaDuocCapQuyen() throws Exception {
        // Lúc khởi động audit_app chưa có: migrate vẫn thành công (callback chỉ cảnh báo), chưa cấp gì
        try (Connection c = connect(LATE_ROLE_DB, postgres.getUsername(), postgres.getPassword());
                Statement s = c.createStatement()) {
            taoVaiTroApp(s, LATE_ROLE_DB);
        }
        assertThat(coDml(LATE_ROLE_DB, "audit_app", "audit_logs")).isFalse();

        // Không có script mới (executed = 0) nhưng afterMigrate vẫn chạy và cấp quyền
        mvc.perform(post("/internal/migrations/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executed").value(0));

        assertThat(coDml(LATE_ROLE_DB, "audit_app", "audit_logs")).isTrue();
        // Callback không phải migration: lịch sử vẫn chỉ gồm các script versioned
        mvc.perform(get("/internal/migrations/audit"))
                .andExpect(jsonPath("$.applied").value(demScript("audit")))
                .andExpect(jsonPath("$.scripts.length()").value(demScript("audit")));
    }

    @Test
    @Order(4)
    void vaiTroAppDuocCapQuyenDuLieuNhungKhongPhaiChuBang() throws Exception {
        String[][] bangMau = {{"identity", "users"}, {"catalog", "titles"}, {"circulation", "borrowings"},
                {"audit", "audit_logs"}, {"system", "menus"}};
        for (String[] t : bangMau) {
            String role = t[0] + "_app";
            try (Connection c = connect(t[0], postgres.getUsername(), postgres.getPassword());
                    Statement s = c.createStatement()) {
                var rs = s.executeQuery("SELECT has_table_privilege('" + role + "', '" + t[1]
                        + "', 'SELECT,INSERT,UPDATE,DELETE'), pg_get_userbyid(c.relowner) <> '" + role
                        + "', (SELECT bool_and(has_table_privilege('" + role + "', format('%I', relname),"
                        + " 'SELECT,INSERT,UPDATE,DELETE')) FROM pg_class WHERE relnamespace = 'public'::regnamespace"
                        + " AND relkind = 'r' AND relname <> 'flyway_schema_history'),"
                        + " has_table_privilege('" + role + "', 'flyway_schema_history', 'INSERT'),"
                        + " has_table_privilege('" + role + "', 'flyway_schema_history', 'UPDATE'),"
                        + " has_table_privilege('" + role + "', 'flyway_schema_history', 'DELETE')"
                        + " FROM pg_class c WHERE c.relname = '" + t[1] + "'");
                rs.next();
                assertThat(rs.getBoolean(1)).as(role + " DML trên " + t[1]).isTrue();
                assertThat(rs.getBoolean(2)).as(role + " không là chủ").isTrue();
                assertThat(rs.getBoolean(3)).as(role + " DML mọi bảng nghiệp vụ").isTrue();
                // Sổ cái migration: vai trò app không được ghi/sửa/xóa
                assertThat(rs.getBoolean(4)).as(role + " INSERT flyway_schema_history").isFalse();
                assertThat(rs.getBoolean(5)).as(role + " UPDATE flyway_schema_history").isFalse();
                assertThat(rs.getBoolean(6)).as(role + " DELETE flyway_schema_history").isFalse();
                // Bảng do migration sau (cùng vai trò chạy migration) tạo được cấp qua ALTER DEFAULT PRIVILEGES
                s.execute("CREATE TABLE bang_cua_migration_sau (id bigserial PRIMARY KEY)");
            }
            try (Connection c = connect(t[0], role, role); Statement s = c.createStatement()) {
                s.execute("INSERT INTO bang_cua_migration_sau DEFAULT VALUES");
                var rs = s.executeQuery("SELECT count(*) FROM bang_cua_migration_sau");
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
                // Không có quyền DDL: không sở hữu bảng nên không DROP được
                assertThatThrownBy(() -> s.execute("DROP TABLE " + t[1]))
                        .hasMessageContaining("must be owner");
            }
        }
    }

    @Test
    @Order(5)
    void targetLaBi404() throws Exception {
        mvc.perform(get("/internal/migrations/khong-co")).andExpect(status().isNotFound());
        mvc.perform(post("/internal/migrations/khong-co/run")).andExpect(status().isNotFound());
    }
}

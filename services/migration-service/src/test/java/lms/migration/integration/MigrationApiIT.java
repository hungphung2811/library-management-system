package lms.migration.integration;

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

    private static final List<String> DBS = List.of("identity", "catalog", "circulation", "audit");

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
                // Vai trò chạy ứng dụng như init-databases.sql: script R__grant_... cấp quyền cho nó
                s.execute("CREATE USER " + db + "_app WITH PASSWORD '" + db + "_app' NOSUPERUSER NOBYPASSRLS");
            }
            String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/" + db
                    + "_db";
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

    /** Số script versioned (V*) và repeatable (R__*) của target, đếm từ classpath. */
    private static int demScript(String target) throws Exception {
        var resolver = new org.springframework.core.io.support.PathMatchingResourcePatternResolver();
        return resolver.getResources("classpath:db/migration/" + target + "/V*.sql").length
                + resolver.getResources("classpath:db/migration/" + target + "/R__*.sql").length;
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
        mvc.perform(get("/api/migrations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(identity + catalog + circulation + audit))
                .andExpect(jsonPath("$.pending").value(0))
                .andExpect(jsonPath("$.targets[?(@.target=='identity')].applied").value(identity))
                .andExpect(jsonPath("$.targets[?(@.target=='catalog')].applied").value(catalog))
                .andExpect(jsonPath("$.targets[?(@.target=='circulation')].applied").value(circulation))
                .andExpect(jsonPath("$.targets[?(@.target=='audit')].applied").value(audit));

        mvc.perform(get("/api/migrations/identity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scripts.length()").value(identity))
                .andExpect(jsonPath("$.scripts[0].version").value("1"))
                .andExpect(jsonPath("$.scripts[0].state").value("APPLIED"))
                .andExpect(jsonPath("$.scripts[0].checksum").isNumber())
                .andExpect(jsonPath("$.scripts[0].installedBy").isNotEmpty());

        mvc.perform(post("/api/migrations/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executed").value(0));
        mvc.perform(post("/api/migrations/identity/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executed").value(0));
    }

    @Test
    @Order(2)
    void scriptMoiBaoPendingRoiChayXongBaoApplied() throws Exception {
        Files.writeString(extraScripts.resolve("V2__them_cot_gia_lap.sql"),
                "ALTER TABLE titles ADD COLUMN IF NOT EXISTS ghi_chu_migration VARCHAR(50);");

        // Số đã áp dụng gồm cả script repeatable (R__grant_...); tìm script mới theo version thay vì vị trí
        int catalog = demScript("catalog");
        mvc.perform(get("/api/migrations/catalog"))
                .andExpect(jsonPath("$.applied").value(catalog))
                .andExpect(jsonPath("$.pending").value(1))
                .andExpect(jsonPath("$.scripts[?(@.version=='2')].state").value("PENDING"));

        mvc.perform(post("/api/migrations/catalog/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executed").value(1));

        mvc.perform(get("/api/migrations/catalog"))
                .andExpect(jsonPath("$.applied").value(catalog + 1))
                .andExpect(jsonPath("$.pending").value(0))
                .andExpect(jsonPath("$.scripts[?(@.version=='2')].state").value("APPLIED"));
    }

    @Test
    @Order(3)
    void vaiTroAppDuocCapQuyenDuLieuNhungKhongPhaiChuBang() throws Exception {
        String[][] bangMau = {{"identity", "users"}, {"catalog", "titles"}, {"circulation", "borrowings"},
                {"audit", "audit_logs"}};
        for (String[] t : bangMau) {
            String role = t[0] + "_app";
            try (Connection c = connect(t[0], postgres.getUsername(), postgres.getPassword());
                    Statement s = c.createStatement()) {
                var rs = s.executeQuery("SELECT has_table_privilege('" + role + "', '" + t[1]
                        + "', 'SELECT,INSERT,UPDATE,DELETE'), pg_get_userbyid(c.relowner) <> '" + role
                        + "', (SELECT bool_and(has_table_privilege('" + role + "', format('%I', relname),"
                        + " 'SELECT,INSERT,UPDATE,DELETE')) FROM pg_class WHERE relnamespace = 'public'::regnamespace"
                        + " AND relkind = 'r' AND relname <> 'flyway_schema_history')"
                        + " FROM pg_class c WHERE c.relname = '" + t[1] + "'");
                rs.next();
                assertThat(rs.getBoolean(1)).as(role + " DML trên " + t[1]).isTrue();
                assertThat(rs.getBoolean(2)).as(role + " không là chủ").isTrue();
                assertThat(rs.getBoolean(3)).as(role + " DML mọi bảng").isTrue();
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
    @Order(4)
    void targetLaBi404() throws Exception {
        mvc.perform(get("/api/migrations/khong-co")).andExpect(status().isNotFound());
        mvc.perform(post("/api/migrations/khong-co/run")).andExpect(status().isNotFound());
    }
}

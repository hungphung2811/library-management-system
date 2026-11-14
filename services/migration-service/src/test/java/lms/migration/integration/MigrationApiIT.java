package lms.migration.integration;

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

    private static int demScript(String target) throws Exception {
        return new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/" + target + "/V*.sql").length;
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

        mvc.perform(get("/api/migrations/catalog"))
                .andExpect(jsonPath("$.applied").value(1))
                .andExpect(jsonPath("$.pending").value(1))
                .andExpect(jsonPath("$.scripts[1].version").value("2"))
                .andExpect(jsonPath("$.scripts[1].state").value("PENDING"));

        mvc.perform(post("/api/migrations/catalog/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executed").value(1));

        mvc.perform(get("/api/migrations/catalog"))
                .andExpect(jsonPath("$.applied").value(2))
                .andExpect(jsonPath("$.pending").value(0))
                .andExpect(jsonPath("$.scripts[1].state").value("APPLIED"));
    }

    @Test
    @Order(3)
    void targetLaBi404() throws Exception {
        mvc.perform(get("/api/migrations/khong-co")).andExpect(status().isNotFound());
        mvc.perform(post("/api/migrations/khong-co/run")).andExpect(status().isNotFound());
    }
}

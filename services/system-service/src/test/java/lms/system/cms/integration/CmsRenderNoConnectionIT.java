package lms.system.cms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import lms.security.permission.PermissionKeyRegistrar;
import lms.system.cms.application.MarkdownRendererPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Cần Docker. Pool Hikari chỉ 1 kết nối: renderer (giả) lấy một kết nối riêng ngay lúc render. Nếu use case còn bọc
 * render trong giao dịch giữ kết nối thì lần lấy này hết hạn (1 giây) và test fail.
 * Chạy: -Dtest=CmsRenderNoConnectionIT -DargLine=-Dapi.version=1.44 -Dsurefire.failIfNoSpecifiedTests=false
 */
@SpringBootTest(properties = {
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "lms.system.menu.cache.enabled=false",
        "migration.run-on-startup=false",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-timeout=1000",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks.json"})
@AutoConfigureMockMvc
@Testcontainers
class CmsRenderNoConnectionIT {

    private static final String APP_ROLE = "system_app";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_ROLE + "' NOSUPERUSER NOBYPASSRLS");
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/system").load().migrate();
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", () -> APP_ROLE);
        r.add("spring.datasource.password", () -> APP_ROLE);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;
    @MockitoBean MarkdownRendererPort renderer;

    private static RequestPostProcessor platformAdmin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("platformAdmin", true));
    }

    @Test
    void renderKhongGiuKetNoiDb() throws Exception {
        AtomicInteger borrowed = new AtomicInteger();
        Mockito.when(renderer.toHtml(Mockito.anyString())).thenAnswer(call -> {
            try (Connection c = dataSource.getConnection()) {
                borrowed.incrementAndGet();
                return "<p>ok</p>";
            } catch (SQLException e) {
                throw new AssertionError("Render đang giữ kết nối DB duy nhất của pool", e);
            }
        });
        String created = mvc.perform(post("/internal/cms/pages").with(platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("slug", "no-conn", "title", "T",
                                "bodyMarkdown", "# x", "sortOrder", 1))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).get("id").asText();
        mvc.perform(post("/internal/cms/pages/" + id + "/publication").with(platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());

        mvc.perform(get("/internal/cms/public/pages/no-conn")).andExpect(status().isOk());

        assertThat(borrowed.get()).isEqualTo(1);
        mvc.perform(get("/internal/cms/pages").with(platformAdmin())).andExpect(status().isOk());
        mvc.perform(get("/internal/cms/public/pages")).andExpect(status().isOk());
    }
}

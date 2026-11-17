package lms.catalog.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lms.security.permission.PermissionKeyRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-0008: không rò biến phiên app.tenant_scope qua pool. Pool Hikari chỉ 1 connection: request ALL của platform admin
 * rồi request của user tenant A dùng lại đúng connection đó, user chỉ được thấy tenant A. Cần Docker.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/catalog",
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=1",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true"})
@AutoConfigureMockMvc
@Testcontainers
class CatalogCrossTenantPoolIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PermissionKeyRegistrar permissionKeyRegistrar;

    final UUID tenantA = UUID.randomUUID();
    final UUID tenantB = UUID.randomUUID();

    private String createTitle(RequestPostProcessor token, String name) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"author\":\"Tac gia\",\"isbn\":\"978-"
                + UUID.randomUUID().toString().substring(0, 8) + "\",\"coverPrice\":1000}";
        String response = mvc.perform(post("/internal/books").with(token).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asText();
    }

    @Test
    void ketNoiTaiDungSauRequestAllKhongRoPhamViChoUserTenantA() throws Exception {
        RequestPostProcessor a = jwt().jwt(j -> j.claim("tenantId", tenantA.toString()).claim("admin", true));
        RequestPostProcessor b = jwt().jwt(j -> j.claim("tenantId", tenantB.toString()).claim("admin", true));
        RequestPostProcessor platform = jwt().jwt(j -> j.claim("platformAdmin", true));
        String keyword = "Pool" + UUID.randomUUID().toString().substring(0, 8);
        String titleA = createTitle(a, keyword + " A");
        createTitle(b, keyword + " B");

        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/internal/books").with(platform).param("keyword", keyword))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
            mvc.perform(get("/internal/books").with(a).param("keyword", keyword))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(titleA));
            mvc.perform(get("/internal/books").with(b).param("keyword", keyword))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
            // chọn một tenant rồi ALL rồi tenant: vẫn đúng
            mvc.perform(get("/internal/books").with(platform).param("keyword", keyword)
                            .param("tenantId", tenantB.toString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        }
    }
}

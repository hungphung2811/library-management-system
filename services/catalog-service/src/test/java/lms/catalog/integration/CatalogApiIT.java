package lms.catalog.integration;

import static org.hamcrest.Matchers.hasSize;
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
 * Cần Docker. Chạy bằng: mvn test -Dtest=CatalogApiIT -Dsurefire.failIfNoSpecifiedTests=false. Bảo mật bật, token mang
 * claim tenantId; ứng dụng kết nối bằng vai trò catalog_app nên chịu RLS (xem {@link AppRoleDatabase}).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/catalog",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true"})
@AutoConfigureMockMvc
@Testcontainers
class CatalogApiIT {

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

    /** Token quản trị của một tenant (bỏ qua kiểm khóa quyền, chỉ để kiểm cách ly dữ liệu). */
    static RequestPostProcessor tenantToken(UUID tenantId) {
        return jwt().jwt(j -> j.claim("admin", true).claim("tenantId", tenantId.toString()));
    }

    @Test
    void taoDauSachNhapBanSaoRoiTimKiemThayBanSaoSanCo() throws Exception {
        RequestPostProcessor token = tenantToken(UUID.randomUUID());
        String body = """
                {"name":"Clean Code","author":"Robert C. Martin","isbn":"9780132350884","coverPrice":250000}""";
        String created = mvc.perform(post("/internal/books").with(token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.availableCopies").value(0))
                .andReturn().getResponse().getContentAsString();
        String titleId = json.readTree(created).get("id").asText();

        mvc.perform(post("/internal/books").with(token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());

        mvc.perform(get("/internal/books").with(token).param("keyword", "clean").param("available", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));

        mvc.perform(post("/internal/book-copies").with(token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"" + titleId + "\",\"barcode\":\"CC-001\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("AVAILABLE"));
        mvc.perform(post("/internal/book-copies").with(token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"" + titleId + "\",\"barcode\":\"CC-001\"}"))
                .andExpect(status().isConflict());

        mvc.perform(get("/internal/books").with(token).param("keyword", "MARTIN").param("available", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].availableCopies").value(1));

        mvc.perform(get("/internal/books/" + titleId).with(token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.availableCopies").value(1));
        mvc.perform(get("/internal/book-copies").with(token).param("titleId", titleId))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(post("/internal/book-copies").with(token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"00000000-0000-0000-0000-000000000009\",\"barcode\":\"ZZ\"}"))
                .andExpect(status().isNotFound());
    }
}

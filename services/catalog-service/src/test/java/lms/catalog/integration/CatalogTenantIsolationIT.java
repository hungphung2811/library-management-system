package lms.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
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
 * Cách ly catalog theo tenant qua HTTP với hai token tenant A/B, ứng dụng chạy bằng vai trò catalog_app (chịu RLS).
 * Cần Docker. Chạy bằng: mvn test -Dtest=CatalogTenantIsolationIT -Dsurefire.failIfNoSpecifiedTests=false.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/catalog",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true"})
@AutoConfigureMockMvc
@Testcontainers
class CatalogTenantIsolationIT {

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

    private static RequestPostProcessor token(UUID tenantId) {
        return jwt().jwt(j -> j.claim("admin", true).claim("tenantId", tenantId.toString()));
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String createTitle(RequestPostProcessor token, String name, String isbn, int expectedStatus) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"author\":\"Tac gia\",\"isbn\":\"" + isbn + "\",\"coverPrice\":100000}";
        String response = mvc.perform(post("/internal/books").with(token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return expectedStatus == 201 ? json.readTree(response).get("id").asText() : null;
    }

    private String createCopy(RequestPostProcessor token, String titleId, String barcode, int expectedStatus) throws Exception {
        String response = mvc.perform(post("/internal/book-copies").with(token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"" + titleId + "\",\"barcode\":\"" + barcode + "\"}"))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return expectedStatus == 201 ? json.readTree(response).get("id").asText() : null;
    }

    @Test
    void cungIsbnVaCungMaVachTaoDuocOHaiTenantNhungTrungTrongMotTenantBiTuChoi() throws Exception {
        RequestPostProcessor a = token(tenantA);
        RequestPostProcessor b = token(tenantB);
        String isbn = "978-DUP-" + suffix();
        String barcode = "DUP-" + suffix();

        String titleA = createTitle(a, "Sach chung", isbn, 201);
        String titleB = createTitle(b, "Sach chung", isbn, 201);
        createTitle(a, "Sach chung 2", isbn, 409);
        createTitle(b, "Sach chung 2", isbn, 409);

        createCopy(a, titleA, barcode, 201);
        createCopy(b, titleB, barcode, 201);
        createCopy(a, titleA, barcode, 409);
        createCopy(b, titleB, barcode, 409);
    }

    @Test
    void duLieuTenantBKhongHienRaChoTokenA() throws Exception {
        RequestPostProcessor a = token(tenantA);
        RequestPostProcessor b = token(tenantB);
        String keyword = "Bimat" + suffix();
        String barcode = "SEC-" + suffix();

        String titleB = createTitle(b, keyword, "978-SEC-" + suffix(), 201);
        String copyB = createCopy(b, titleB, barcode, 201);

        mvc.perform(get("/internal/books").with(a).param("keyword", keyword)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/internal/books").with(b).param("keyword", keyword)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/internal/books/" + titleB).with(a)).andExpect(status().isNotFound());
        mvc.perform(get("/internal/book-copies/" + copyB).with(a)).andExpect(status().isNotFound());
        mvc.perform(get("/internal/book-copies").with(a).param("titleId", titleB)).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        // thêm bản sao vào đầu sách của tenant khác coi như đầu sách không tồn tại (truy vấn native titleExists)
        createCopy(a, titleB, "X-" + barcode, 404);
        mvc.perform(put("/internal/book-copies/" + copyB + "/status").with(a)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LOST\"}"))
                .andExpect(status().isNotFound());
        // truy vấn native findDetailsByBarcode/findDetailsById không trả bản sao tenant khác (Review Focus 1)
        mvc.perform(get("/internal/book-copies/by-barcode/" + barcode).with(a)).andExpect(status().isNotFound());
        mvc.perform(get("/internal/book-copies/by-barcode/" + barcode).with(b)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(copyB));
        mvc.perform(get("/internal/book-copies/" + copyB).with(b)).andExpect(status().isOk());
    }

    @Test
    void lookupChiTraDauSachCuaTenantTrongTokenVaBoIdTenantKhac() throws Exception {
        RequestPostProcessor a = token(tenantA);
        RequestPostProcessor b = token(tenantB);
        String titleA = createTitle(a, "Lookup A " + suffix(), "978-LKA-" + suffix(), 201);
        String titleB = createTitle(b, "Lookup B " + suffix(), "978-LKB-" + suffix(), 201);
        String missing = UUID.randomUUID().toString();
        String body = "{\"ids\":[\"" + titleB + "\",\"" + titleA + "\",\"" + missing + "\"]}";

        mvc.perform(post("/internal/books/lookup").with(a).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(titleA))
                .andExpect(jsonPath("$[0].coverPrice").value(100000));
        mvc.perform(post("/internal/books/lookup").with(b).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(titleB));
    }

    @Test
    void tokenKhongCoTenantIdBi403TenantRequired() throws Exception {
        RequestPostProcessor noTenant = jwt().jwt(j -> j.claim("admin", true));
        mvc.perform(get("/internal/books").with(noTenant).param("keyword", "x"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        mvc.perform(post("/internal/books").with(noTenant).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"n\",\"author\":\"a\",\"coverPrice\":1}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    @Test
    void rlsKhongCoTenantThiKhongThayDongNao() throws Exception {
        String titleId = createTitle(token(tenantA), "RLS " + suffix(), "978-RLS-" + suffix(), 201);
        assertThat(titleId).isNotNull();
        try (Connection c = AppRoleDatabase.appRole(postgres); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("select count(*) from titles")) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }
            try (ResultSet rs = s.executeQuery("select count(*) from book_copies")) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }
        }
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from titles where tenant_id = '" + tenantA + "'")) {
            rs.next();
            assertThat(rs.getLong(1)).isPositive();
        }
    }
}

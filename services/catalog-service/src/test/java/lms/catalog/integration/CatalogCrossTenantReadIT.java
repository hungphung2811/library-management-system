package lms.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ADR-0008 (catalog): platform admin (platformAdmin=true, không tenantId) đọc xuyên tenant ở GET /internal/books và
 * GET /internal/book-copies?titleId=. Chạy bằng vai trò catalog_app (NOBYPASSRLS). Cần Docker. Chạy bằng:
 * mvn test -Dtest=CatalogCrossTenantReadIT -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/catalog",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true"})
@AutoConfigureMockMvc
@Testcontainers
class CatalogCrossTenantReadIT {

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

    static RequestPostProcessor tenantToken(UUID tenantId, boolean admin) {
        java.util.List<String> keys = java.util.List.of("TitleController/search", "BookCopyController/list");
        return jwt().jwt(j -> j.claim("admin", admin).claim("tenantId", tenantId.toString())
                        .claim("permissions", keys))
                .authorities(keys.stream()
                        .<org.springframework.security.core.GrantedAuthority>map(
                                org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                        .toList());
    }

    static RequestPostProcessor platformAdmin() {
        return jwt().jwt(j -> j.claim("platformAdmin", true));
    }

    static RequestPostProcessor platformAdminOfTenant(UUID tenantId) {
        return jwt().jwt(j -> j.claim("platformAdmin", true).claim("tenantId", tenantId.toString()));
    }

    static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    String createTitle(RequestPostProcessor token, String name) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"author\":\"Tac gia\",\"isbn\":\"978-" + suffix()
                + "\",\"coverPrice\":100000}";
        String response = mvc.perform(post("/internal/books").with(token).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asText();
    }

    String createCopy(RequestPostProcessor token, String titleId, String barcode) throws Exception {
        String response = mvc.perform(post("/internal/book-copies").with(token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"" + titleId + "\",\"barcode\":\"" + barcode + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asText();
    }

    void loan(RequestPostProcessor token, String copyId) throws Exception {
        mvc.perform(put("/internal/book-copies/" + copyId + "/status").with(token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LOANED\"}"))
                .andExpect(status().isOk());
    }

    /** Hai tenant cùng có một đầu sách trùng từ khóa: A có 2 bản (1 đang mượn), B có 1 bản sẵn có. */
    record Seed(String keyword, String titleA, String titleB) {
    }

    Seed seed() throws Exception {
        String keyword = "Xuyen" + suffix();
        RequestPostProcessor a = tenantToken(tenantA, true);
        RequestPostProcessor b = tenantToken(tenantB, true);
        String titleA = createTitle(a, keyword + " A");
        String titleB = createTitle(b, keyword + " B");
        createCopy(a, titleA, "A1-" + suffix());
        String a2 = createCopy(a, titleA, "A2-" + suffix());
        loan(a, a2);
        createCopy(b, titleB, "B1-" + suffix());
        return new Seed(keyword, titleA, titleB);
    }

    JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private static JsonNode byId(JsonNode array, String id) {
        for (JsonNode n : array) {
            if (id.equals(n.get("id").asText())) {
                return n;
            }
        }
        return null;
    }

    // --- cổng kiểm chứng isRoot ---

    @Test
    void platformAdminKhongTenantIdThayCaHaiTenantVaAvailableCopiesDung() throws Exception {
        Seed s = seed();
        JsonNode list = body(mvc.perform(get("/internal/books").with(platformAdmin()).param("keyword", s.keyword()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2)));
        JsonNode ta = byId(list, s.titleA());
        JsonNode tb = byId(list, s.titleB());
        assertThat(ta).isNotNull();
        assertThat(tb).isNotNull();
        assertThat(ta.get("tenantId").asText()).isEqualTo(tenantA.toString());
        assertThat(tb.get("tenantId").asText()).isEqualTo(tenantB.toString());
        assertThat(ta.get("availableCopies").asLong()).isEqualTo(1);
        assertThat(tb.get("availableCopies").asLong()).isEqualTo(1);

        // lọc available=true trong bộ nhớ vẫn đúng ở ALL
        mvc.perform(get("/internal/books").with(platformAdmin()).param("keyword", s.keyword()).param("available", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));

        JsonNode copiesA = body(mvc.perform(get("/internal/book-copies").with(platformAdmin())
                        .param("titleId", s.titleA()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2)));
        for (JsonNode c : copiesA) {
            assertThat(c.get("tenantId").asText()).isEqualTo(tenantA.toString());
        }
        JsonNode copiesB = body(mvc.perform(get("/internal/book-copies").with(platformAdmin())
                        .param("titleId", s.titleB()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)));
        assertThat(copiesB.get(0).get("tenantId").asText()).isEqualTo(tenantB.toString());
    }

    @Test
    void platformAdminTenantIdATheoDungMotTenant() throws Exception {
        Seed s = seed();
        mvc.perform(get("/internal/books").with(platformAdmin()).param("keyword", s.keyword())
                        .param("tenantId", tenantA.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(s.titleA()))
                .andExpect(jsonPath("$[0].tenantId").value(tenantA.toString()))
                .andExpect(jsonPath("$[0].availableCopies").value(1));
        mvc.perform(get("/internal/book-copies").with(platformAdmin()).param("titleId", s.titleA())
                        .param("tenantId", tenantA.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        // titleId của tenant B nhưng chọn tenant A: không thấy gì
        mvc.perform(get("/internal/book-copies").with(platformAdmin()).param("titleId", s.titleB())
                        .param("tenantId", tenantA.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void platformAdminTenantIdKhongTonTaiTra200DanhSachRong() throws Exception {
        Seed s = seed();
        String unknown = UUID.randomUUID().toString();
        mvc.perform(get("/internal/books").with(platformAdmin()).param("keyword", s.keyword()).param("tenantId", unknown))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/internal/book-copies").with(platformAdmin()).param("titleId", s.titleA())
                        .param("tenantId", unknown))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void platformAdminTenantIdSaiDinhDangTra400() throws Exception {
        mvc.perform(get("/internal/books").with(platformAdmin()).param("tenantId", "khong-phai-uuid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TENANT_ID"));
        mvc.perform(get("/internal/book-copies").with(platformAdmin()).param("titleId", UUID.randomUUID().toString())
                        .param("tenantId", "khong-phai-uuid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TENANT_ID"));
    }

    // --- người gọi khác bỏ qua tenantId ---

    @Test
    void nguoiDungThuongVaAdminTenantGuiTenantIdBVanChiThayTenantA() throws Exception {
        Seed s = seed();
        for (boolean admin : new boolean[] {false, true}) {
            RequestPostProcessor a = tenantToken(tenantA, admin);
            JsonNode list = body(mvc.perform(get("/internal/books").with(a).param("keyword", s.keyword())
                            .param("tenantId", tenantB.toString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)));
            assertThat(list.get(0).get("id").asText()).isEqualTo(s.titleA());
            mvc.perform(get("/internal/book-copies").with(a).param("titleId", s.titleB())
                            .param("tenantId", tenantB.toString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
            mvc.perform(get("/internal/book-copies").with(a).param("titleId", s.titleA())
                            .param("tenantId", tenantB.toString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
            // tenantId sai định dạng cũng bị bỏ qua (không 400)
            mvc.perform(get("/internal/books").with(a).param("keyword", s.keyword()).param("tenantId", "abc"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        }
    }

    @Test
    void platformAdminDaChonTenantTrongTokenBoQuaThamSoTenantId() throws Exception {
        Seed s = seed();
        // token có tenantId được coi như người dùng của tenant đó (không admin tenant, không khóa) nên bị 403 FORBIDDEN,
        // quan trọng là không đọc được tenant B qua tham số.
        RequestPostProcessor pa = platformAdminOfTenant(tenantA);
        mvc.perform(get("/internal/books").with(pa).param("keyword", s.keyword()).param("tenantId", tenantB.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    void platformAdminKhongTenantVanBiTuChoiGhiVaDocTheoId() throws Exception {
        Seed s = seed();
        long titlesBefore = count("titles");
        long copiesBefore = count("book_copies");
        mvc.perform(post("/internal/books").with(platformAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ghi\",\"author\":\"a\",\"coverPrice\":1}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        mvc.perform(post("/internal/book-copies").with(platformAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"titleId\":\"" + s.titleA() + "\",\"barcode\":\"GHI-" + suffix() + "\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        mvc.perform(get("/internal/books/" + s.titleA()).with(platformAdmin()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        assertThat(count("titles")).isEqualTo(titlesBefore);
        assertThat(count("book_copies")).isEqualTo(copiesBefore);
    }

    private long count(String table) throws Exception {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select count(*) from " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}

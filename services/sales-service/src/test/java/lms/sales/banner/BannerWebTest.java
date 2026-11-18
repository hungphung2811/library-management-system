package lms.sales.banner;

import static lms.sales.support.WebTokens.TENANT;
import static lms.sales.support.WebTokens.admin;
import static lms.sales.support.WebTokens.storefrontService;
import static lms.sales.support.WebTokens.withKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import lms.sales.banner.application.BannerAdminUseCases;
import lms.sales.banner.application.BannerAdminView;
import lms.sales.banner.application.PublicBannerQuery;
import lms.sales.banner.infrastructure.web.SalesBannerController;
import lms.sales.banner.infrastructure.web.StorefrontBannerController;
import lms.sales.config.SecurityConfiguration;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * ADR-0019 B3: ma trận khóa x endpoint x module với kiểm khóa bật lại, token dịch vụ (svc) chỉ vào được endpoint
 * công khai, tập tên trường JSON, mã lỗi. Nghiệp vụ thật chạy trên bộ giả trong bộ nhớ (tenant của token là TENANT).
 */
@WebMvcTest({SalesBannerController.class, StorefrontBannerController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class,
        BannerWebTest.Beans.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class BannerWebTest {

    @TestConfiguration
    static class Beans {
        @Bean
        BannerFixture bannerFixture() {
            return new BannerFixture();
        }

        @Bean
        BannerAdminUseCases admin(BannerFixture f) {
            return f.admin;
        }

        @Bean
        PublicBannerQuery publicQuery(BannerFixture f) {
            return f.publicQuery;
        }
    }

    private static final String LIST = "SalesBannerController/list";
    private static final String GET = "SalesBannerController/get";
    private static final String CREATE = "SalesBannerController/create";
    private static final String UPDATE = "SalesBannerController/update";
    private static final String DELETE = "SalesBannerController/delete";
    private static final String PUBLIC_LIST = "StorefrontBannerController/list";
    private static final List<String> ADMIN_KEYS = List.of(LIST, GET, CREATE, UPDATE, DELETE);
    private static final String B = "/internal/sales/banners";
    private static final String S = "/internal/sales/storefront/banners";

    @Autowired MockMvc mvc;
    @Autowired BannerFixture f;
    @Autowired ObjectMapper json;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    @org.junit.jupiter.api.BeforeEach
    void resetFixture() {
        f.rows.clear();
        f.audits.clear();
        f.now = BannerFixture.T0;
    }

    private static String body(String title, String link) {
        return "{\"title\":\"" + title + "\",\"subtitle\":\"Đến hết 30/11\",\"linkPath\":"
                + (link == null ? "null" : "\"" + link + "\"")
                + ",\"sortOrder\":0,\"active\":true,\"startsAt\":\"2026-11-10T00:00:00+07:00\","
                + "\"endsAt\":\"2026-12-01T00:00:00+07:00\"}";
    }

    private static String putBody(String title, long version) {
        return body(title, "/books").replaceFirst("}$", ",\"version\":" + version + "}");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor staff(String... keys) {
        return withKeys(TENANT, List.of("SALES"), keys);
    }

    private BannerAdminView seed(String title) {
        return f.admin.create(TENANT, f.staff, BannerFixture.cmd(title));
    }

    private List<MockHttpServletRequestBuilder> adminEndpoints(UUID id) {
        return List.of(get(B), get(B + "/" + id),
                post(B).contentType(MediaType.APPLICATION_JSON).content(body("A", null)),
                put(B + "/" + id).contentType(MediaType.APPLICATION_JSON).content(putBody("A", 0)),
                delete(B + "/" + id));
    }

    // ---- CRUD ----

    @Test
    void taoDocSuaXoaDungMaVaTapTenTruong() throws Exception {
        String created = mvc.perform(post(B).with(staff(CREATE)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Giảm 20%", "/books?sort=newest")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("LIVE"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode node = json.readTree(created);
        assertThat(fieldNames(node)).containsExactly("active", "createdAt", "endsAt", "id", "linkPath", "sortOrder",
                "startsAt", "status", "subtitle", "title", "updatedAt", "version");
        String id = node.get("id").asText();

        mvc.perform(get(B + "/" + id).with(staff(GET))).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Giảm 20%"));
        mvc.perform(get(B).with(staff(LIST))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(put(B + "/" + id).with(staff(UPDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content(putBody("Mới", 0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.title").value("Mới"));
        mvc.perform(delete(B + "/" + id).with(staff(DELETE))).andExpect(status().isNoContent());
        mvc.perform(get(B + "/" + id).with(staff(GET))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BANNER_NOT_FOUND"));
    }

    @Test
    void linkSai400InvalidBannerChiNeuTenTruongKhongLoGiaTri() throws Exception {
        String secret = "javascript:alert(1)";
        String res = mvc.perform(post(B).with(staff(CREATE)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("A", secret)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_BANNER"))
                .andReturn().getResponse().getContentAsString();
        assertThat(res).contains("field=linkPath").doesNotContain("javascript");
        assertThat(f.rows).isEmpty();
    }

    @Test
    void suaThieuVersionVaJsonHong400ValidationFailed() throws Exception {
        UUID id = seed("A").id();
        mvc.perform(put(B + "/" + id).with(staff(UPDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("A", null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post(B).with(staff(CREATE)).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void versionCu409VaBanner21409() throws Exception {
        UUID id = seed("A").id();
        mvc.perform(put(B + "/" + id).with(staff(UPDATE)).contentType(MediaType.APPLICATION_JSON)
                        .content(putBody("B", 5)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BANNER_VERSION_CONFLICT"));
        for (int i = 0; i < 19; i++) {
            seed("B" + i);
        }
        mvc.perform(post(B).with(staff(CREATE)).contentType(MediaType.APPLICATION_JSON).content(body("X", null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BANNER_LIMIT_EXCEEDED"));
    }

    @Test
    void idCuaTenantKhac404() throws Exception {
        BannerAdminView other = f.admin.create(UUID.randomUUID(), f.staff, BannerFixture.cmd("khac"));
        mvc.perform(get(B + "/" + other.id()).with(staff(GET))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BANNER_NOT_FOUND"));
        mvc.perform(delete(B + "/" + other.id()).with(staff(DELETE))).andExpect(status().isNotFound());
    }

    // ---- công khai ----

    @Test
    void congKhaiDung5TruongVaChiBannerDangHieuLuc() throws Exception {
        f.now = java.time.Instant.parse("2026-11-20T00:00:00Z");
        f.admin.create(TENANT, f.staff, new BannerAdminUseCases.SaveCommand("Hiện", "sub", "/books", 1, true,
                null, null));
        f.admin.create(TENANT, f.staff, new BannerAdminUseCases.SaveCommand("Tắt", null, null, 0, false, null, null));
        String res = mvc.perform(get(S).with(storefrontService(TENANT, List.of("SALES"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode first = json.readTree(res).get(0);
        assertThat(fieldNames(first)).containsExactly("id", "linkPath", "sortOrder", "subtitle", "title");
    }

    @Test
    void congKhaiRongLa200MangRong() throws Exception {
        mvc.perform(get(S).with(storefrontService(TENANT, List.of("SALES")))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---- khóa quyền ----

    @Test
    void moiEndpointQuanTriChiMoVoiDungKhoa() throws Exception {
        UUID id = seed("A").id();
        for (int i = 0; i < ADMIN_KEYS.size(); i++) {
            for (int k = 0; k < ADMIN_KEYS.size(); k++) {
                var res = mvc.perform(adminEndpoints(id).get(i).with(staff(ADMIN_KEYS.get(k))));
                if (i == k) {
                    int code = res.andReturn().getResponse().getStatus();
                    assertThat(code).as(ADMIN_KEYS.get(i)).isNotEqualTo(403).isNotEqualTo(401);
                } else {
                    res.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
                }
            }
        }
    }

    @Test
    void khongKhoaVaTokenDichVuBi403OMoiEndpointQuanTri() throws Exception {
        UUID id = seed("A").id();
        for (int i = 0; i < ADMIN_KEYS.size(); i++) {
            mvc.perform(adminEndpoints(id).get(i).with(staff())).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            // SalesBannerController không @StorefrontAccessible: token svc bị chặn kể cả không cần khóa
            mvc.perform(adminEndpoints(id).get(i).with(storefrontService(TENANT, List.of("SALES"))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void endpointCongKhaiTokenThuongCanKhoaRieng() throws Exception {
        mvc.perform(get(S).with(staff(LIST))).andExpect(status().isForbidden());
        mvc.perform(get(S).with(staff(PUBLIC_LIST))).andExpect(status().isOk());
    }

    @Test
    void chuaBatSalesLa403ModuleNotEnabledKeCaAdmin() throws Exception {
        UUID id = seed("A").id();
        for (int i = 0; i < ADMIN_KEYS.size(); i++) {
            mvc.perform(adminEndpoints(id).get(i).with(withKeys(TENANT, List.of("LENDING"), ADMIN_KEYS.get(i))))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
            mvc.perform(adminEndpoints(id).get(i).with(admin(List.of("LENDING")))).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
        }
        mvc.perform(get(S).with(storefrontService(TENANT, List.of("LENDING")))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
    }

    @Test
    void adminTenantBoQuaKiemKhoa() throws Exception {
        mvc.perform(get(B).with(admin())).andExpect(status().isOk());
    }

    @Test
    void khongTokenLa401() throws Exception {
        mvc.perform(get(B)).andExpect(status().isUnauthorized());
        mvc.perform(get(S)).andExpect(status().isUnauthorized());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>(new TreeSet<>(iterate(node)));
        return names;
    }

    private static List<String> iterate(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}

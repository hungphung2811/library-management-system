package lms.system.cms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lms.security.permission.PermissionKeyRegistrar;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T-C3 bổ sung (ADR-0011): ca đối kháng cho context cms trên PostgreSQL thật, vai trò system_app. Ký tự NUL, độ dài
 * biên, slug lạ ở đường dẫn công khai, Markdown lồng sâu (DoS khi render), tranh chấp phiên bản song song, token
 * dịch vụ/khóa/role không phải platform admin. Cần Docker. Chạy: mvn -pl services/system-service -am test
 * -Dtest=CmsAdversarialIT -Dsurefire.failIfNoSpecifiedTests=false -DargLine=-Dapi.version=1.44
 */
@SpringBootTest(properties = {
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "lms.system.menu.cache.enabled=false",
        "migration.run-on-startup=false",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks.json"})
@AutoConfigureMockMvc
@Testcontainers
class CmsAdversarialIT {

    private static final String APP_ROLE = "system_app";
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_ROLE + "' NOSUPERUSER NOBYPASSRLS");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration/system").load().migrate();
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", () -> APP_ROLE);
        r.add("spring.datasource.password", () -> APP_ROLE);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static RequestPostProcessor platformAdmin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("platformAdmin", true));
    }

    private MvcResult postJson(String url, Object body, RequestPostProcessor auth) throws Exception {
        var b = post(url).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (auth != null) b = b.with(auth);
        return mvc.perform(b).andReturn();
    }

    private JsonNode createPage(String slug, String title, String body) throws Exception {
        MvcResult r = postJson("/internal/cms/pages", Map.of("slug", slug, "title", title, "bodyMarkdown", body,
                "sortOrder", 1), platformAdmin());
        assertThat(r.getResponse().getStatus()).as("tạo %s: %s", slug, r.getResponse().getContentAsString())
                .isEqualTo(201);
        return json.readTree(r.getResponse().getContentAsString());
    }

    private Map<String, Object> contact(String name, String email, String message) {
        return Map.of("fullName", name, "email", email, "phone", "0901234567", "organization", "Org",
                "businessType", "LIBRARY", "message", message, "consent", true);
    }

    // ---------- đầu vào độc hại ở API công khai ----------

    @Test
    void kyTuNulTrongFormLienHeKhongGay500() throws Exception {
        for (Map<String, Object> body : List.of(
                contact("A\u0000B", "nul-1@example.com", "m"),
                contact("A", "nul-2@example.com", "xin\u0000chao"),
                contact("A", "nul\u0000-3@example.com", "m"))) {
            MvcResult r = postJson("/internal/cms/public/contact-requests", body, null);
            assertThat(r.getResponse().getStatus()).as("NUL trong form: %s", r.getResponse().getContentAsString())
                    .isBetween(400, 499);
            assertThat(r.getResponse().getContentAsString()).doesNotContain("org.postgresql", "Exception", "SQL");
        }
    }

    @Test
    void emailSaiDinhDangVaTruongToanKhoangTrangBi400() throws Exception {
        for (Map<String, Object> body : List.of(
                contact("A", "khong-phai-email", "m"),
                contact("A", "a@b", "m"),
                contact("A", "a b@example.com", "m"),
                contact("   ", "ws-1@example.com", "m"),
                contact("A", "ws-2@example.com", "   "))) {
            MvcResult r = postJson("/internal/cms/public/contact-requests", body, null);
            assertThat(r.getResponse().getStatus()).as("%s", body).isEqualTo(400);
        }
    }

    @Test
    void slugLaOApiCongKhaiLa404KhongPhai500() throws Exception {
        for (String slug : List.of("ABC", "a_b", "a%00b", "..", "%2e%2e", "a%0A", "x".repeat(200), "a b", "<script>",
                "%E1%BB%8D", "a;b")) {
            MvcResult r = mvc.perform(get("/internal/cms/public/pages/" + slug)).andReturn();
            assertThat(r.getResponse().getStatus()).as("slug %s", slug).isIn(400, 404);
            assertThat(r.getResponse().getContentAsString()).doesNotContain("Exception", "org.postgresql");
        }
    }

    // ---------- Markdown lồng sâu: render lúc đọc công khai ----------

    @Test
    void markdownLongSauKhongLamSapEndpointCongKhai() throws Exception {
        List<String> bombs = List.of(
                ">".repeat(49_000),
                "[".repeat(24_000) + "x" + "]".repeat(24_000),
                "- ".repeat(24_000) + "x",
                "*".repeat(49_000),
                "_a".repeat(24_000),
                "`".repeat(49_000),
                "<".repeat(49_000),
                "![".repeat(10_000) + "x" + "](http://a)".repeat(2_000),
                ("  ".repeat(100) + "- a\n").repeat(200),
                "\\".repeat(49_000));
        int i = 0;
        List<String> failures = new ArrayList<>();
        for (String bomb : bombs) {
            int idx = i++;
            String slug = "bomb-" + idx;
            JsonNode page = createPage(slug, "Bomb " + idx, bomb);
            postJson("/internal/cms/pages/" + page.get("id").asText() + "/publication", Map.of("version",
                    page.get("version").asLong()), platformAdmin());
            long t0 = System.nanoTime();
            try {
                MvcResult r = mvc.perform(get("/internal/cms/public/pages/" + slug)).andReturn();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                System.out.println("MD-BOMB #" + idx + " status=" + r.getResponse().getStatus() + " " + ms + "ms");
                if (r.getResponse().getStatus() != 200 || ms >= 5_000
                        || r.getResponse().getContentAsString().contains("<script")) {
                    failures.add("bom #" + idx + " [" + bomb.substring(0, Math.min(6, bomb.length())) + "...] status="
                            + r.getResponse().getStatus() + " " + ms + "ms");
                }
            } catch (Throwable t) {
                failures.add("bom #" + idx + " [" + bomb.substring(0, Math.min(6, bomb.length())) + "...] ném "
                        + t.getClass().getSimpleName() + (t.getCause() != null ? "/" + t.getCause().getClass().getSimpleName() : ""));
            }
        }
        assertThat(failures).as("Markdown lồng sâu làm hỏng đọc công khai").isEmpty();
    }

    // ---------- biên độ dài / slug ở API quản trị ----------

    @Test
    void bienDoDaiVaSlugCuaTrang() throws Exception {
        // đúng biên: 80 ký tự slug, 200 ký tự tiêu đề, 50 000 ký tự nội dung => 201
        createPage("s".repeat(80), "t".repeat(200), "b".repeat(50_000));
        // vượt biên
        assertThat(postJson("/internal/cms/pages", Map.of("slug", "s".repeat(81), "title", "t", "bodyMarkdown", ""),
                platformAdmin()).getResponse().getStatus()).isEqualTo(400);
        assertThat(postJson("/internal/cms/pages", Map.of("slug", "ok-title", "title", "t".repeat(201),
                "bodyMarkdown", ""), platformAdmin()).getResponse().getStatus()).isEqualTo(400);
        MvcResult tooLong = postJson("/internal/cms/pages", Map.of("slug", "ok-body", "title", "t",
                "bodyMarkdown", "b".repeat(50_001)), platformAdmin());
        assertThat(tooLong.getResponse().getStatus()).isEqualTo(400);
        assertThat(tooLong.getResponse().getContentAsString()).contains("CMS_CONTENT_TOO_LONG");
        for (String bad : List.of("A", "a_b", "a b", "ä", "a/b", "")) {
            MvcResult r = postJson("/internal/cms/pages", Map.of("slug", bad, "title", "t", "bodyMarkdown", ""),
                    platformAdmin());
            assertThat(r.getResponse().getStatus()).as("slug [%s]", bad).isEqualTo(400);
        }
        // NUL trong nội dung quản trị
        MvcResult nul = postJson("/internal/cms/pages", Map.of("slug", "nul-body", "title", "t",
                "bodyMarkdown", "a\u0000b"), platformAdmin());
        assertThat(nul.getResponse().getStatus()).as("NUL trong body: %s", nul.getResponse().getContentAsString())
                .isBetween(400, 499);
    }

    @Test
    void trangSlugTrungKhongPhanBietHoaThuongKhongTaoDuocHaiLan() throws Exception {
        createPage("dup-check", "A", "x");
        MvcResult again = postJson("/internal/cms/pages", Map.of("slug", "dup-check", "title", "B", "bodyMarkdown", ""),
                platformAdmin());
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(again.getResponse().getContentAsString()).contains("CMS_SLUG_DUPLICATED");
    }

    // ---------- tranh chấp phiên bản song song ----------

    @Test
    void haiLanSuaCungPhienBanSongSongChiMotThanhCong() throws Exception {
        JsonNode page = createPage("race-edit", "R", "v0");
        String id = page.get("id").asText();
        long version = page.get("version").asLong();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String body = "{\"title\":\"R\",\"bodyMarkdown\":\"thread-" + i + "\",\"sortOrder\":1,\"version\":"
                        + version + "}";
                tasks.add(() -> mvc.perform(put("/internal/cms/pages/" + id).with(platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus());
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(tasks)) codes.add(f.get());
            assertThat(codes).as("mã trả về của 8 lần sửa song song").containsOnly(200, 409);
            assertThat(codes.stream().filter(c -> c == 200).count()).as("đúng một lần thắng").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- phân quyền quản trị ----------

    @Test
    void chiPlatformAdminDuocDungApiQuanTriCmsMoiLoaiTokenKhacBi403Hoac401() throws Exception {
        List<String> urls = List.of("/internal/cms/pages", "/internal/cms/contact-requests");
        List<RequestPostProcessor> notAllowed = List.of(
                jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("admin", true)
                        .claim("tenantId", TENANT.toString())),
                jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("tenantId", TENANT.toString())
                        .claim("permissions", List.of("CmsPageAdminController/list", "CmsPageAdminController/create",
                                "ContactRequestAdminController/search"))),
                // token dịch vụ storefront
                jwt().jwt(j -> j.subject("00000000-0000-0000-0000-000000005f01").claim("svc", "customer-api")
                        .claim("tenantId", TENANT.toString()).claim("modules", List.of("SALES"))),
                // token không admin, không tenant (vd mustChangePassword)
                jwt().jwt(j -> j.subject(UUID.randomUUID().toString())),
                // platformAdmin dạng chuỗi/false không được coi là true
                jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("platformAdmin", false)),
                jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("platformAdmin", "true")));
        for (String url : urls) {
            assertThat(mvc.perform(get(url)).andReturn().getResponse().getStatus()).as("ẩn danh %s", url).isEqualTo(401);
            int n = 0;
            for (RequestPostProcessor token : notAllowed) {
                MvcResult r = mvc.perform(get(url).with(token)).andReturn();
                assertThat(r.getResponse().getStatus()).as("%s loại token #%d", url, n++).isEqualTo(403);
                assertThat(r.getResponse().getContentAsString()).doesNotContain("CmsPageAdminController/");
            }
        }
        for (RequestPostProcessor token : notAllowed) {
            assertThat(postJson("/internal/cms/pages", Map.of("slug", "hack", "title", "t", "bodyMarkdown", ""), token)
                    .getResponse().getStatus()).isEqualTo(403);
        }
        assertThat(mvc.perform(get("/internal/cms/pages")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void trangNhapKhongLoRaDanhSachCongKhaiKeCaKhiAdminDangSua() throws Exception {
        JsonNode draft = createPage("only-draft", "Nháp bí mật", "NOI-DUNG-BI-MAT-NHAP");
        JsonNode published = createPage("is-live", "Bản công khai", "CONG-KHAI-V1");
        long v = published.get("version").asLong();
        MvcResult pub = postJson("/internal/cms/pages/" + published.get("id").asText() + "/publication",
                Map.of("version", v), platformAdmin());
        long v1 = json.readTree(pub.getResponse().getContentAsString()).get("version").asLong();
        mvc.perform(put("/internal/cms/pages/" + published.get("id").asText()).with(platformAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"TIEU-DE-DANG-SUA\",\"bodyMarkdown\":\"NOI-DUNG-DANG-SUA\",\"sortOrder\":1,\"version\":"
                        + v1 + "}")).andReturn();

        String list = mvc.perform(get("/internal/cms/public/pages")).andReturn().getResponse().getContentAsString();
        assertThat(list).contains("is-live", "Bản công khai").doesNotContain("only-draft", "Nháp bí mật", "TIEU-DE-DANG-SUA");
        String one = mvc.perform(get("/internal/cms/public/pages/is-live")).andReturn().getResponse().getContentAsString();
        assertThat(one).contains("CONG-KHAI-V1").doesNotContain("NOI-DUNG-DANG-SUA", "TIEU-DE-DANG-SUA");
        MvcResult draftGet = mvc.perform(get("/internal/cms/public/pages/only-draft")).andReturn();
        assertThat(draftGet.getResponse().getStatus()).isEqualTo(404);
        assertThat(draftGet.getResponse().getContentAsString()).doesNotContain("NOI-DUNG-BI-MAT-NHAP", "Nháp bí mật");
        assertThat(draft.get("id").asText()).isNotBlank();
        // tìm theo id ở đường dẫn công khai cũng không ra
        assertThat(mvc.perform(get("/internal/cms/public/pages/" + draft.get("id").asText())).andReturn().getResponse()
                .getStatus()).isEqualTo(404);
    }
}

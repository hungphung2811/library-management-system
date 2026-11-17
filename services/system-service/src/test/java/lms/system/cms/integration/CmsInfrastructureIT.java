package lms.system.cms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import lms.security.permission.PermissionKeyRegistrar;
import lms.system.cms.infrastructure.scheduling.ContactAnonymizationJob;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
 * Cần Docker. Chạy: mvn -pl services/system-service -am test -Dtest=CmsInfrastructureIT
 * -Dsurefire.failIfNoSpecifiedTests=false (Docker 29 thêm -DargLine=-Dapi.version=1.44).
 *
 * <p>Postgres thật: Flyway chạy bằng superuser của container (chủ bảng, V1-V3 + afterMigrate của system), ứng dụng
 * kết nối bằng vai trò system_app (NOSUPERUSER NOBYPASSRLS) nên kiểm luôn việc cấp quyền cho bảng cms_*. Kiểm: bản nháp
 * không lộ, sửa trang đã xuất bản không lộ bản đang sửa, XSS bị vô hiệu, platform admin 200 và tenant admin 403, form
 * liên hệ, job ẩn danh (idempotent, chỉ đụng dòng quá hạn), dữ liệu cá nhân không có trong log.
 */
@SpringBootTest(properties = {
        "app.security.enabled=true",
        "lms.security.permission.enabled=true",
        "lms.system.menu.cache.enabled=false",
        "migration.run-on-startup=false",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/jwks.json"})
@AutoConfigureMockMvc
@Testcontainers
class CmsInfrastructureIT {

    private static final String APP_ROLE = "system_app";
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final String PII_EMAIL = "pii-test-9f3a@example.com";
    private static final String PII_NAME = "Nguoi Gui Rieng Tu";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        try (Connection c = superuser(); Statement s = c.createStatement()) {
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
    @Autowired ContactAnonymizationJob job;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    private static Connection superuser() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static RequestPostProcessor platformAdmin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("platformAdmin", true));
    }

    private static RequestPostProcessor tenantAdmin() {
        return jwt().jwt(j -> j.subject(UUID.randomUUID().toString()).claim("admin", true)
                .claim("tenantId", TENANT.toString()));
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private JsonNode createPage(String slug, String title, String markdown) throws Exception {
        return body(mvc.perform(post("/internal/cms/pages").with(platformAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("slug", slug, "title", title,
                                "bodyMarkdown", markdown, "sortOrder", 1))))
                .andExpect(status().isCreated()));
    }

    private JsonNode publish(String id, long version) throws Exception {
        return body(mvc.perform(post("/internal/cms/pages/" + id + "/publication").with(platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + version + "}"))
                .andExpect(status().isOk()));
    }

    private String submitContactBody(String name, String email) throws Exception {
        return json.writeValueAsString(java.util.Map.of("fullName", name, "email", email, "phone", "0901234567",
                "organization", "Thư viện IT", "businessType", "LIBRARY", "message", "Muốn dùng thử", "consent", true));
    }

    private JsonNode submitContact(String name, String email) throws Exception {
        mvc.perform(post("/internal/cms/public/contact-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(submitContactBody(name, email)))
                .andExpect(status().isCreated());
        JsonNode list = body(mvc.perform(get("/internal/cms/contact-requests").with(platformAdmin())
                .param("size", "100")).andExpect(status().isOk()));
        for (JsonNode item : list.get("items")) {
            if (email.equals(item.get("email").asText(null))) {
                return item;
            }
        }
        throw new AssertionError("Không thấy yêu cầu vừa gửi");
    }

    // ---------- trang ----------

    @Test
    void banNhapKhongLoRoiXuatBanRoiSuaKhongLoBanDangSua() throws Exception {
        JsonNode page = createPage("it-gioi-thieu", "Giới thiệu", "# Xin chào\n\nBản đầu");
        String id = page.get("id").asText();
        assertThat(page.get("status").asText()).isEqualTo("DRAFT");

        // Nháp: công khai không thấy trang
        mvc.perform(get("/internal/cms/public/pages/it-gioi-thieu")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CMS_PAGE_NOT_FOUND"));
        assertThat(body(mvc.perform(get("/internal/cms/public/pages")).andExpect(status().isOk())).toString())
                .doesNotContain("it-gioi-thieu");

        // Xuất bản: thấy bản đã xuất bản dưới dạng HTML
        JsonNode published = publish(id, page.get("version").asLong());
        assertThat(published.get("status").asText()).isEqualTo("PUBLISHED");
        mvc.perform(get("/internal/cms/public/pages/it-gioi-thieu")).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Giới thiệu"))
                .andExpect(jsonPath("$.bodyHtml").value(org.hamcrest.Matchers.containsString("<h1>Xin chào</h1>")))
                .andExpect(jsonPath("$.bodyMarkdown").doesNotExist());

        // Sửa bản làm việc: công khai vẫn là bản cũ
        mvc.perform(put("/internal/cms/pages/" + id).with(platformAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"TIÊU ĐỀ ĐANG SỬA\",\"bodyMarkdown\":\"NỘI DUNG ĐANG SỬA\","
                                + "\"sortOrder\":1,\"version\":" + published.get("version").asLong() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED_WITH_CHANGES"));
        String publicNow = body(mvc.perform(get("/internal/cms/public/pages/it-gioi-thieu"))
                .andExpect(status().isOk())).toString();
        assertThat(publicNow).contains("Xin chào").doesNotContain("ĐANG SỬA");
        assertThat(body(mvc.perform(get("/internal/cms/public/pages")).andExpect(status().isOk())).toString())
                .contains("Giới thiệu").doesNotContain("ĐANG SỬA");

        // Gỡ xuất bản: biến mất khỏi công khai, nhưng admin vẫn thấy bản làm việc
        mvc.perform(delete("/internal/cms/pages/" + id + "/publication").with(platformAdmin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
        mvc.perform(get("/internal/cms/public/pages/it-gioi-thieu")).andExpect(status().isNotFound());
        mvc.perform(get("/internal/cms/pages/" + id).with(platformAdmin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.bodyMarkdown").value("NỘI DUNG ĐANG SỬA"));
    }

    @Test
    void markdownDocHaiRaHtmlVoHai() throws Exception {
        JsonNode page = createPage("it-xss", "XSS", "<script>alert(1)</script>\n\n[bấm](javascript:alert(2))\n\n"
                + "![x](javascript:alert(3))\n\n<img src=x onerror=alert(4)>");
        publish(page.get("id").asText(), page.get("version").asLong());

        String html = body(mvc.perform(get("/internal/cms/public/pages/it-xss")).andExpect(status().isOk()))
                .get("bodyHtml").asText();

        assertThat(html).doesNotContain("<script", "<img src=x").doesNotContainIgnoringCase("javascript:");
        // Ảnh Markdown hợp lệ vẫn ra thẻ img nhưng src bị bỏ (rỗng)
        assertThat(html).contains("<img src=\"\" alt=\"x\"");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    void phienBanCuVaSlugTrungBi409() throws Exception {
        JsonNode page = createPage("it-conflict", "T", "b");
        String id = page.get("id").asText();
        long v0 = page.get("version").asLong();
        mvc.perform(put("/internal/cms/pages/" + id).with(platformAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T2\",\"bodyMarkdown\":\"b2\",\"sortOrder\":1,\"version\":" + v0 + "}"))
                .andExpect(status().isOk());

        mvc.perform(post("/internal/cms/pages/" + id + "/publication").with(platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":" + v0 + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CMS_VERSION_CONFLICT"));
        mvc.perform(post("/internal/cms/pages").with(platformAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"it-conflict\",\"title\":\"X\",\"bodyMarkdown\":\"y\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CMS_SLUG_DUPLICATED"));
        mvc.perform(post("/internal/cms/pages").with(platformAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"Sai Slug\",\"title\":\"X\",\"bodyMarkdown\":\"y\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CMS_SLUG_INVALID"));
        mvc.perform(post("/internal/cms/pages").with(platformAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("slug", "it-dai", "title", "X",
                                "bodyMarkdown", "y".repeat(50_001)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CMS_CONTENT_TOO_LONG"));
    }

    @Test
    void xoaTrang() throws Exception {
        JsonNode page = createPage("it-xoa", "Xóa", "b");
        String id = page.get("id").asText();
        publish(id, page.get("version").asLong());

        mvc.perform(delete("/internal/cms/pages/" + id).with(platformAdmin())).andExpect(status().isNoContent());

        mvc.perform(get("/internal/cms/public/pages/it-xoa")).andExpect(status().isNotFound());
        mvc.perform(get("/internal/cms/pages/" + id).with(platformAdmin())).andExpect(status().isNotFound());
    }

    @Test
    void tenantAdminBi403VaKhongTokenBi401OApiQuanTri() throws Exception {
        mvc.perform(get("/internal/cms/pages").with(tenantAdmin())).andExpect(status().isForbidden());
        mvc.perform(get("/internal/cms/contact-requests").with(tenantAdmin())).andExpect(status().isForbidden());
        mvc.perform(get("/internal/cms/pages")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/cms/pages").with(platformAdmin())).andExpect(status().isOk());
    }

    // ---------- yêu cầu liên hệ ----------

    @Test
    void formLienHeLuuDuocXuLyTrangThaiVaAnDanhNgay() throws Exception {
        JsonNode item = submitContact("Nguoi Gui A", "contact-a@example.com");
        String id = item.get("id").asText();
        JsonNode detail = body(mvc.perform(get("/internal/cms/contact-requests/" + id).with(platformAdmin()))
                .andExpect(status().isOk()));
        assertThat(detail.get("status").asText()).isEqualTo("NEW");
        assertThat(detail.get("phone").asText()).isEqualTo("0901234567");

        mvc.perform(put("/internal/cms/contact-requests/" + id + "/status").with(platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"HANDLED\",\"version\":" + detail.get("version").asLong() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HANDLED"));
        mvc.perform(put("/internal/cms/contact-requests/" + id + "/status").with(platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\",\"version\":" + detail.get("version").asLong() + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONTACT_REQUEST_VERSION_CONFLICT"));

        mvc.perform(post("/internal/cms/contact-requests/" + id + "/anonymization").with(platformAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.fullName").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.organization").value("Thư viện IT"))
                .andExpect(jsonPath("$.status").value("HANDLED"));
        mvc.perform(post("/internal/cms/contact-requests/" + id + "/anonymization").with(platformAdmin()))
                .andExpect(status().isOk());
        mvc.perform(put("/internal/cms/contact-requests/" + id + "/status").with(platformAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\",\"version\":99}"))
                .andExpect(status().isConflict());
        try (Connection c = superuser(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT full_name, email, phone, message, anonymized_at "
                     + "FROM cms_contact_requests WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isNull();
            assertThat(rs.getString(2)).isNull();
            assertThat(rs.getString(3)).isNull();
            assertThat(rs.getString(4)).isNull();
            assertThat(rs.getTimestamp(5)).isNotNull();
        }
    }

    @Test
    void formThieuDongYHoacThieuTruongBi400KhongLuu() throws Exception {
        mvc.perform(post("/internal/cms/public/contact-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(submitContactBody("A", "no-consent@example.com").replace("\"consent\":true",
                                "\"consent\":false")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONSENT_REQUIRED"));
        mvc.perform(post("/internal/cms/public/contact-requests").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"consent\":true}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/internal/cms/public/contact-requests").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("fullName", "A", "email", "x@example.com",
                                "organization", "O", "businessType", "OTHER", "message", "m".repeat(2001),
                                "consent", true))))
                .andExpect(status().isBadRequest());
        try (Connection c = superuser(); PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM cms_contact_requests WHERE email IN ('no-consent@example.com', 'x@example.com')");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    @Test
    void jobAnDanhChiDongQuaHanChayLaiKhongDoiVaLogKhongCoDuLieuCaNhan() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            String oldHandled = submitContact(PII_NAME, "old-handled-" + PII_EMAIL).get("id").asText();
            String oldNew = submitContact(PII_NAME, "old-new-" + PII_EMAIL).get("id").asText();
            String fresh = submitContact(PII_NAME, "fresh-" + PII_EMAIL).get("id").asText();
            JsonNode d = body(mvc.perform(get("/internal/cms/contact-requests/" + oldHandled).with(platformAdmin()))
                    .andExpect(status().isOk()));
            mvc.perform(put("/internal/cms/contact-requests/" + oldHandled + "/status").with(platformAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"SPAM\",\"version\":" + d.get("version").asLong() + "}"))
                    .andExpect(status().isOk());
            try (Connection c = superuser(); PreparedStatement ps = c.prepareStatement(
                    "UPDATE cms_contact_requests SET created_at = now() - interval '400 days' WHERE id IN (?::uuid, ?::uuid)")) {
                ps.setString(1, oldHandled);
                ps.setString(2, oldNew);
                assertThat(ps.executeUpdate()).isEqualTo(2);
            }

            assertThat(job.runOnce()).isEqualTo(2);
            assertThat(job.runOnce()).isZero();

            assertThat(isAnonymized(oldHandled)).isTrue();
            assertThat(isAnonymized(oldNew)).isTrue();
            assertThat(isAnonymized(fresh)).isFalse();
            mvc.perform(get("/internal/cms/contact-requests/" + oldHandled).with(platformAdmin()))
                    .andExpect(jsonPath("$.status").value("SPAM")).andExpect(jsonPath("$.email").doesNotExist());
            mvc.perform(get("/internal/cms/contact-requests/" + fresh).with(platformAdmin()))
                    .andExpect(jsonPath("$.email").value("fresh-" + PII_EMAIL));
        } finally {
            root.detachAppender(appender);
        }

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(m -> m.contains(PII_EMAIL) || m.contains(PII_NAME));
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.contains("operation=contact-requests-anonymized count=2"));
    }

    private static boolean isAnonymized(String id) throws SQLException {
        try (Connection c = superuser(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT anonymized_at IS NOT NULL FROM cms_contact_requests WHERE id = '"
                     + id + "'")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }
}

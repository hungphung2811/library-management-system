package lms.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
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
 * ADR-0011 B-C6: mô tả và đoạn đọc thử của đầu sách trên DB thật (migration V1..V4, vai trò catalog_app chịu RLS).
 * Cần Docker. Chạy: mvn -pl services/catalog-service -am test -Dtest=CatalogPreviewIT -DargLine=-Dapi.version=1.44
 * -Dsurefire.failIfNoSpecifiedTests=false
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/catalog",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true"})
@AutoConfigureMockMvc
@Testcontainers
class CatalogPreviewIT {

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

    private static RequestPostProcessor admin(UUID tenantId) {
        return jwt().jwt(j -> j.claim("admin", true).claim("tenantId", tenantId.toString()));
    }

    private static RequestPostProcessor withKey(UUID tenantId, String key) {
        return jwt().jwt(j -> j.claim("tenantId", tenantId.toString()).claim("permissions", List.of(key)))
                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(key));
    }

    private static RequestPostProcessor svc(UUID tenantId) {
        return jwt().jwt(j -> j.claim("svc", "customer-api").claim("tenantId", tenantId.toString())
                .claim("modules", List.of("SALES")).claim("permissions", List.of()));
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String createTitle(UUID tenant, String name) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"author\":\"Tac gia\",\"isbn\":\"978-" + suffix()
                + "\",\"coverPrice\":100000}";
        String response = mvc.perform(post("/internal/books").with(admin(tenant)).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("id").asText();
    }

    private org.springframework.test.web.servlet.ResultActions putPreview(RequestPostProcessor token, String id,
                                                                          String description, String previewText)
            throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("description", description);
        body.put("previewText", previewText);
        return mvc.perform(put("/internal/books/" + id + "/preview").with(token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    @Test
    void migrationV4ThemHaiCotVaGiuNguyenRls() throws Exception {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("select column_name, character_maximum_length, is_nullable "
                    + "from information_schema.columns where table_name = 'titles' "
                    + "and column_name in ('description','preview_text') order by column_name")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("description");
                assertThat(rs.getInt(2)).isEqualTo(500);
                assertThat(rs.getString(3)).isEqualTo("YES");
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("preview_text");
                assertThat(rs.getInt(2)).isEqualTo(2000);
                assertThat(rs.getString(3)).isEqualTo("YES");
            }
            try (ResultSet rs = s.executeQuery(
                    "select relrowsecurity, relforcerowsecurity from pg_class where relname = 'titles'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBoolean(1)).isTrue();
                assertThat(rs.getBoolean(2)).isTrue();
            }
        }
    }

    @Test
    void chenThangDbQuaDaiHoacRongBiChan() throws Exception {
        String id = createTitle(tenantA, "Chen thang " + suffix());
        try (Connection c = AppRoleDatabase.superuser(postgres)) {
            assertThatThrownBy(() -> update(c, id, "preview_text", "a".repeat(2001)))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> update(c, id, "description", "b".repeat(501))).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> update(c, id, "preview_text", "   ")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> update(c, id, "description", "")).isInstanceOf(SQLException.class);
            update(c, id, "preview_text", "a".repeat(2000));
        }
    }

    private static void update(Connection c, String id, String column, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("update titles set " + column + " = ? where id = ?::uuid")) {
            ps.setString(1, value);
            ps.setString(2, id);
            ps.executeUpdate();
        }
    }

    @Test
    void dauSachMoiChuaCoPreviewVaDuLieuCuConNguyen() throws Exception {
        String id = createTitle(tenantA, "Chua co " + suffix());
        mvc.perform(get("/internal/books/" + id + "/preview").with(admin(tenantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.description").value((Object) null))
                .andExpect(jsonPath("$.previewText").value((Object) null));
        mvc.perform(post("/internal/books/lookup").with(admin(tenantA)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + id + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hasPreview").value(false))
                .andExpect(jsonPath("$[0].description").value((Object) null));
    }

    @Test
    void preview2000LuuDuocVa2001Tra400KhongLuuMoTa501Tra400() throws Exception {
        String id = createTitle(tenantA, "Do dai " + suffix());
        putPreview(admin(tenantA), id, "mo ta ban dau", "a".repeat(2000)).andExpect(status().isOk());

        putPreview(admin(tenantA), id, "mo ta moi", "b".repeat(2001))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TITLE_PREVIEW_TOO_LONG"));
        putPreview(admin(tenantA), id, "c".repeat(501), "ok")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TITLE_PREVIEW_TOO_LONG"));

        // bản cũ còn nguyên: lần ghi lỗi không lưu gì
        mvc.perform(get("/internal/books/" + id + "/preview").with(admin(tenantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("mo ta ban dau"))
                .andExpect(jsonPath("$.previewText").value("a".repeat(2000)));
        putPreview(admin(tenantA), id, "d".repeat(500), "ok").andExpect(status().isOk());
    }

    @Test
    void emoji2000CodePointLuuDuocVaNfcCuaTiengVietDuocChuanHoa() throws Exception {
        String id = createTitle(tenantA, "Unicode " + suffix());
        String emoji = new String(Character.toChars(0x1F600));
        putPreview(admin(tenantA), id, emoji.repeat(500), emoji.repeat(2000)).andExpect(status().isOk());
        mvc.perform(get("/internal/books/" + id + "/preview").with(admin(tenantA)))
                .andExpect(jsonPath("$.previewText").value(emoji.repeat(2000)));
        putPreview(admin(tenantA), id, null, emoji.repeat(2001)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TITLE_PREVIEW_TOO_LONG"));

        String decomposed = Normalizer.normalize("Việt Nam", Normalizer.Form.NFD);
        putPreview(admin(tenantA), id, decomposed, decomposed.repeat(200)).andExpect(status().isOk());
        String nfc = Normalizer.normalize("Việt Nam", Normalizer.Form.NFC);
        mvc.perform(get("/internal/books/" + id + "/preview").with(admin(tenantA)))
                .andExpect(jsonPath("$.description").value(nfc))
                .andExpect(jsonPath("$.previewText").value(nfc.repeat(200)));
    }

    @Test
    void kyTuDieuKhienBiBoRongThanhNullVaVanBanThuanTraNguyenVan() throws Exception {
        String id = createTitle(tenantA, "Chuan hoa " + suffix());
        String raw = "<script>alert(1)</script> & \"q\"";
        putPreview(admin(tenantA), id, "  a\u0000b\u0007  ", raw + "\r\nhet\u0001").andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("ab"))
                .andExpect(jsonPath("$.previewText").value(raw + "\nhet"));
        putPreview(admin(tenantA), id, "   ", "\n\t").andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value((Object) null))
                .andExpect(jsonPath("$.previewText").value((Object) null));
        mvc.perform(post("/internal/books/lookup").with(admin(tenantA)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + id + "\"]}"))
                .andExpect(jsonPath("$[0].hasPreview").value(false));
    }

    @Test
    void lookupCoDescriptionVaHasPreviewKhongCoPreviewTextVaSuaThongTinChinhGiuPreview() throws Exception {
        String id = createTitle(tenantA, "Giu preview " + suffix());
        putPreview(admin(tenantA), id, "mo ta", "doc thu").andExpect(status().isOk());
        mvc.perform(post("/internal/books/lookup").with(admin(tenantA)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + id + "\"]}"))
                .andExpect(jsonPath("$[0].description").value("mo ta"))
                .andExpect(jsonPath("$[0].hasPreview").value(true))
                .andExpect(jsonPath("$[0].previewText").doesNotExist());
        mvc.perform(put("/internal/books/" + id).with(admin(tenantA)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ten moi\",\"author\":\"Tac gia\",\"coverPrice\":5}"))
                .andExpect(status().isOk());
        mvc.perform(get("/internal/books/" + id + "/preview").with(admin(tenantA)))
                .andExpect(jsonPath("$.name").value("Ten moi"))
                .andExpect(jsonPath("$.description").value("mo ta"))
                .andExpect(jsonPath("$.previewText").value("doc thu"));
    }

    @Test
    void tenantKhacKhongDocKhongSuaDuocPreview() throws Exception {
        String idB = createTitle(tenantB, "Cua B " + suffix());
        putPreview(admin(tenantB), idB, "mo ta B", "doc thu B").andExpect(status().isOk());

        mvc.perform(get("/internal/books/" + idB + "/preview").with(admin(tenantA))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TITLE_NOT_FOUND"));
        mvc.perform(get("/internal/books/" + idB + "/preview").with(svc(tenantA))).andExpect(status().isNotFound());
        putPreview(admin(tenantA), idB, "hack", "hack").andExpect(status().isNotFound());
        mvc.perform(get("/internal/books/" + idB + "/preview").with(admin(tenantB)))
                .andExpect(jsonPath("$.previewText").value("doc thu B"));
    }

    @Test
    void tokenDichVuDocDuocNhungKhongGhiDuoc() throws Exception {
        String id = createTitle(tenantA, "Svc " + suffix());
        putPreview(admin(tenantA), id, "mo ta", "doc thu").andExpect(status().isOk());

        mvc.perform(get("/internal/books/" + id + "/preview").with(svc(tenantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewText").value("doc thu"))
                .andExpect(jsonPath("$.isbn").doesNotExist())
                .andExpect(jsonPath("$.coverPrice").doesNotExist());
        mvc.perform(post("/internal/books/lookup").with(svc(tenantA)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + id + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].previewText").doesNotExist());
        putPreview(svc(tenantA), id, "hack", "hack").andExpect(status().isForbidden());
        mvc.perform(get("/internal/books/" + id).with(svc(tenantA))).andExpect(status().isForbidden());
        // không bị ghi đè
        mvc.perform(get("/internal/books/" + id + "/preview").with(admin(tenantA)))
                .andExpect(jsonPath("$.previewText").value("doc thu"));
    }

    @Test
    void khoaDongPreviewVaUpdatePreviewTachRieng() throws Exception {
        String id = createTitle(tenantA, "Khoa " + suffix());
        putPreview(withKey(tenantA, "TitleController/updatePreview"), id, "m", "p").andExpect(status().isOk());
        mvc.perform(get("/internal/books/" + id + "/preview").with(withKey(tenantA, "TitleController/updatePreview")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/internal/books/" + id + "/preview").with(withKey(tenantA, "TitleController/preview")))
                .andExpect(status().isOk());
        putPreview(withKey(tenantA, "TitleController/preview"), id, "x", "y").andExpect(status().isForbidden());
    }

    @Test
    void lookupTheoTuKhoaTheoTenantVaToiDa200() throws Exception {
        String keyword = "Tukhoa" + suffix();
        createTitle(tenantA, keyword + " mot");
        createTitle(tenantA, keyword + " hai");
        createTitle(tenantB, keyword + " cua B");
        mvc.perform(get("/internal/books/lookup").param("keyword", keyword).with(svc(tenantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].hasPreview").value(false));
        mvc.perform(get("/internal/books/lookup").param("keyword", keyword).with(svc(tenantB)))
                .andExpect(jsonPath("$", hasSize(1)));
    }
}

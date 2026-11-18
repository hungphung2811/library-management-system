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
import java.sql.SQLException;
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
 * Migration V7 (10 trường bổ sung của đầu sách) trên DB thật: Hibernate validate schema (cột text[] của tags), ghi và đọc
 * lại qua API bằng vai trò catalog_app, PUT thiếu trường thì xóa, CHECK ở DB chặn chèn thẳng dữ liệu sai.
 * Cần Docker. Chạy: mvn -pl services/catalog-service -am test -Dtest=CatalogTitleExtrasIT -DargLine=-Dapi.version=1.44
 * -Dsurefire.failIfNoSpecifiedTests=false
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/catalog",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true"})
@AutoConfigureMockMvc
@Testcontainers
class CatalogTitleExtrasIT {

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

    final UUID tenant = UUID.randomUUID();

    private RequestPostProcessor admin() {
        return jwt().jwt(j -> j.claim("admin", true).claim("tenantId", tenant.toString()));
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static final String EXTRAS = ",\"subtitle\":\"Phụ đề\",\"publicationYear\":2017,\"edition\":\"Tái bản lần thứ 8\","
            + "\"language\":\"vi\",\"pageCount\":368,\"format\":\"PAPERBACK\",\"translator\":\"Lý Lan\","
            + "\"series\":\"Harry Potter\",\"seriesNumber\":1,\"coverImageUrl\":\"https://img.example/hp.jpg\","
            + "\"tags\":[\"Giả tưởng\",\"giả tưởng\",\"Phép thuật\"]";

    @Test
    void taoDocSuaVaXoaThongTinBoSungQuaApi() throws Exception {
        String isbn = "978-" + suffix();
        String created = mvc.perform(post("/internal/books").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"HP\",\"author\":\"Rowling\",\"isbn\":\"" + isbn + "\",\"coverPrice\":1000"
                                + EXTRAS + "}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tags", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).get("id").asText();

        mvc.perform(get("/internal/books/" + id).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtitle").value("Phụ đề"))
                .andExpect(jsonPath("$.publicationYear").value(2017))
                .andExpect(jsonPath("$.edition").value("Tái bản lần thứ 8"))
                .andExpect(jsonPath("$.language").value("vi"))
                .andExpect(jsonPath("$.pageCount").value(368))
                .andExpect(jsonPath("$.format").value("PAPERBACK"))
                .andExpect(jsonPath("$.translator").value("Lý Lan"))
                .andExpect(jsonPath("$.series").value("Harry Potter"))
                .andExpect(jsonPath("$.seriesNumber").value(1))
                .andExpect(jsonPath("$.coverImageUrl").value("https://img.example/hp.jpg"))
                .andExpect(jsonPath("$.tags[0]").value("Giả tưởng"))
                .andExpect(jsonPath("$.tags[1]").value("Phép thuật"));

        // tìm kiếm cũng trả đủ trường
        mvc.perform(get("/internal/books").param("keyword", "HP").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].language").value("vi"))
                .andExpect(jsonPath("$[0].tags", hasSize(2)));

        // PUT thiếu trường bổ sung nghĩa là xóa
        mvc.perform(put("/internal/books/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"HP\",\"author\":\"Rowling\",\"isbn\":\"" + isbn + "\",\"coverPrice\":1000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtitle").doesNotExist())
                .andExpect(jsonPath("$.tags", hasSize(0)));
        mvc.perform(get("/internal/books/" + id).with(admin()))
                .andExpect(jsonPath("$.language").doesNotExist())
                .andExpect(jsonPath("$.format").doesNotExist())
                .andExpect(jsonPath("$.tags", hasSize(0)));

        // PUT gửi lại đủ thì ghi lại được
        mvc.perform(put("/internal/books/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"HP\",\"author\":\"Rowling\",\"isbn\":\"" + isbn + "\",\"coverPrice\":1000"
                                + EXTRAS + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageCount").value(368));
    }

    @Test
    void soTapKhongCoBoSachTra422() throws Exception {
        mvc.perform(post("/internal/books").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"author\":\"Y\",\"coverPrice\":1,\"seriesNumber\":2}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TITLE"));
    }

    @Test
    void checkOCsdlChanChenThangDuLieuSai() throws Exception {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            s.execute("select set_config('app.tenant_id', '" + tenant + "', false)");
            String base = "insert into titles (id, tenant_id, name, author, cover_price, %s) values ('"
                    + "%s', '" + tenant + "', 'n', 'a', 1, %s)";
            String[] bad = {
                    "series_number|1",
                    "series, series_number|'S', 0",
                    "language|'VI'",
                    "language|'v'",
                    "page_count|0",
                    "page_count|20001",
                    "format|'AUDIO'",
                    "cover_image_url|'ftp://x.example/a'",
                    "publication_year|999",
                    "tags|array['1','2','3','4','5','6','7','8','9','10','11']",
                    "subtitle|'   '"};
            for (String b : bad) {
                String[] parts = b.split("\\|", 2);
                String sql = String.format(base, parts[0], UUID.randomUUID(), parts[1]);
                assertThatThrownBy(() -> s.execute(sql)).as(b).isInstanceOf(SQLException.class)
                        .hasMessageContaining("violates check constraint");
            }
        }
    }
}

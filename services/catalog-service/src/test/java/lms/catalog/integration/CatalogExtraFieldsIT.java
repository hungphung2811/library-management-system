package lms.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * Migration V8 (thông tin bổ sung của bản sao, tác giả, nhà xuất bản, vị trí kệ) trên DB thật: Hibernate validate schema,
 * ghi và đọc lại qua API bằng vai trò catalog_app, PUT thay toàn bộ, CHECK ở DB chặn chèn thẳng dữ liệu sai.
 * Cần Docker. Chạy: mvn -pl services/catalog-service -am test -Dtest=CatalogExtraFieldsIT
 * "-DargLine=-Dapi.version=1.44 -Dspring.kafka.bootstrap-servers=localhost:1 -Dspring.kafka.consumer.group-id=lms-it"
 * -Dsurefire.failIfNoSpecifiedTests=false (hai cờ kafka để IT không nối vào Kafka dev).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/catalog",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true"})
@AutoConfigureMockMvc
@Testcontainers
class CatalogExtraFieldsIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    final UUID tenant = UUID.randomUUID();

    private RequestPostProcessor admin() {
        return jwt().jwt(j -> j.claim("admin", true).claim("tenantId", tenant.toString())
                .claim("modules", java.util.List.of("LENDING")));
    }

    private String create(String path, String body) throws Exception {
        String res = mvc.perform(post(path).with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("id").asText();
    }

    @Test
    void banSaoGhiDocSuaVaXoaThongTinNhapKho() throws Exception {
        String title = create("/internal/books", "{\"name\":\"T\",\"author\":\"A\",\"isbn\":\"978-" + UUID.randomUUID()
                .toString().substring(0, 8) + "\",\"coverPrice\":100000}");
        String acq = "\"acquiredDate\":\"2024-05-01\",\"acquisitionSource\":\"PURCHASE\",\"purchasePrice\":60000,"
                + "\"conditionGrade\":\"GOOD\",\"conditionNote\":\"Gáy mòn\",\"lastInventoryDate\":\"2026-01-10\"";
        String copy = create("/internal/book-copies", "{\"titleId\":\"" + title + "\",\"barcode\":\"EX-"
                + UUID.randomUUID().toString().substring(0, 8) + "\"," + acq + "}");

        mvc.perform(get("/internal/book-copies").param("titleId", title).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].acquiredDate").value("2024-05-01"))
                .andExpect(jsonPath("$[0].acquisitionSource").value("PURCHASE"))
                .andExpect(jsonPath("$[0].purchasePrice").value(60000))
                .andExpect(jsonPath("$[0].conditionGrade").value("GOOD"))
                .andExpect(jsonPath("$[0].conditionNote").value("Gáy mòn"))
                .andExpect(jsonPath("$[0].lastInventoryDate").value("2026-01-10"));

        // đổi trạng thái không làm mất thông tin nhập kho
        mvc.perform(put("/internal/book-copies/" + copy + "/status").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"LOANED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOANED"))
                .andExpect(jsonPath("$.purchasePrice").value(60000));

        // PUT thay toàn bộ: nguồn DONATION, không giá
        mvc.perform(put("/internal/book-copies/" + copy + "/acquisition").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acquiredDate\":\"2025-01-01\",\"acquisitionSource\":\"DONATION\","
                                + "\"conditionGrade\":\"FAIR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acquisitionSource").value("DONATION"))
                .andExpect(jsonPath("$.purchasePrice").doesNotExist())
                .andExpect(jsonPath("$.conditionNote").doesNotExist())
                .andExpect(jsonPath("$.lastInventoryDate").doesNotExist())
                .andExpect(jsonPath("$.status").value("LOANED"));

        // giá mua khi nguồn không phải PURCHASE: 422
        mvc.perform(put("/internal/book-copies/" + copy + "/acquisition").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acquisitionSource\":\"EXCHANGE\",\"purchasePrice\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_COPY"));
    }

    @Test
    void tacGiaNhaXuatBanViTriKeGhiDocVaPutThayToanBo() throws Exception {
        String author = create("/internal/authors", "{\"name\":\"Hawking-" + UUID.randomUUID() + "\",\"birthYear\":1942,"
                + "\"deathYear\":2018,\"nationality\":\"Anh\",\"photoUrl\":\"https://x.example/h.jpg\","
                + "\"website\":\"https://h.example\"}");
        mvc.perform(get("/internal/authors/" + author).with(admin()))
                .andExpect(jsonPath("$.birthYear").value(1942))
                .andExpect(jsonPath("$.deathYear").value(2018))
                .andExpect(jsonPath("$.nationality").value("Anh"))
                .andExpect(jsonPath("$.photoUrl").value("https://x.example/h.jpg"))
                .andExpect(jsonPath("$.website").value("https://h.example"));
        mvc.perform(put("/internal/authors/" + author).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hawking-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.birthYear").doesNotExist())
                .andExpect(jsonPath("$.website").doesNotExist());

        String publisher = create("/internal/publishers", "{\"name\":\"NXB-" + UUID.randomUUID() + "\",\"country\":\"Việt Nam\","
                + "\"foundedYear\":1981,\"website\":\"https://nxb.example\",\"address\":\"TP.HCM\"}");
        mvc.perform(get("/internal/publishers/" + publisher).with(admin()))
                .andExpect(jsonPath("$.country").value("Việt Nam"))
                .andExpect(jsonPath("$.foundedYear").value(1981))
                .andExpect(jsonPath("$.address").value("TP.HCM"));

        String shelf = create("/internal/shelf-locations", "{\"code\":\"K-" + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"floor\":\"Tầng 2\",\"zone\":\"Khu Văn học\",\"capacity\":60}");
        mvc.perform(get("/internal/shelf-locations/" + shelf).with(admin()))
                .andExpect(jsonPath("$.floor").value("Tầng 2"))
                .andExpect(jsonPath("$.zone").value("Khu Văn học"))
                .andExpect(jsonPath("$.capacity").value(60));
    }

    @Test
    void checkOCsdlChanChenThangDuLieuSai() throws Exception {
        try (Connection c = AppRoleDatabase.superuser(postgres); Statement s = c.createStatement()) {
            String[] bad = {
                    "authors|name, name_key, birth_year|'a1','a1',0",
                    "authors|name, name_key, birth_year, death_year|'a2','a2',1900,1899",
                    "authors|name, name_key, photo_url|'a3','a3','ftp://x'",
                    "authors|name, name_key, nationality|'a4','a4','  '",
                    "publishers|name, name_key, founded_year|'p1','p1',1399",
                    "publishers|name, name_key, website|'p2','p2','x.example'",
                    "shelf_locations|code, code_key, capacity|'s1','s1',0",
                    "shelf_locations|code, code_key, capacity|'s2','s2',100001",
                    "shelf_locations|code, code_key, floor|'s3','s3','   '"};
            for (String b : bad) {
                String[] p = b.split("\\|", 3);
                String sql = "insert into " + p[0] + " (id, tenant_id, " + p[1] + ") values ('" + UUID.randomUUID()
                        + "', '" + tenant + "', " + p[2] + ")";
                assertThatThrownBy(() -> s.execute(sql)).as(b).isInstanceOf(SQLException.class)
                        .hasMessageContaining("violates check constraint");
            }
            // book_copies: cần titles làm khóa ngoại
            UUID titleId = UUID.randomUUID();
            s.execute("insert into titles (id, tenant_id, name, author, cover_price) values ('" + titleId + "', '" + tenant
                    + "', 't', 'a', 1)");
            String[] badCopies = {
                    "acquisition_source|'GIFT'",
                    "purchase_price|5",
                    "purchase_price, acquisition_source|-1,'PURCHASE'",
                    "purchase_price, acquisition_source|5,'DONATION'",
                    "condition_grade|'BROKEN'",
                    "condition_note|'   '",
                    "acquired_date, last_inventory_date|'2025-02-01','2025-01-01'"};
            for (String b : badCopies) {
                String[] p = b.split("\\|", 2);
                String sql = "insert into book_copies (id, tenant_id, title_id, barcode, status, " + p[0]
                        + ") values ('" + UUID.randomUUID() + "', '" + tenant + "', '" + titleId + "', 'B-"
                        + UUID.randomUUID() + "', 'AVAILABLE', " + p[1] + ")";
                assertThatThrownBy(() -> s.execute(sql)).as(b).isInstanceOf(SQLException.class)
                        .hasMessageContaining("violates check constraint");
            }
        }
        assertThat(true).isTrue();
    }
}

package lms.catalog.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import lms.security.permission.PermissionKeyRegistrar;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
 * ADR-0026 pha 1b trên DB thật (vai trò catalog_app, RLS): POST /internal/books không workId tự tạo tác phẩm 1:1 (tương thích
 * ngược), gắn ấn bản thứ hai vào tác phẩm có sẵn, sửa trường cấp tác phẩm qua ấn bản đi vào work và ghi gương, tìm/chi tiết
 * tác phẩm, gộp, tách (changeWork), bộ sách, và số câu SQL của danh sách không tăng theo số dòng.
 * Chạy: mvn -pl services/catalog-service -am test -Dtest=CatalogWorksApiIT -DargLine=-Dapi.version=1.44
 * -Dsurefire.failIfNoSpecifiedTests=false
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration/catalog",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "app.security.enabled=true",
        "lms.security.permission.enabled=true"})
@AutoConfigureMockMvc
@Testcontainers
class CatalogWorksApiIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        AppRoleDatabase.register(postgres, registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SessionFactory sessionFactory;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PermissionKeyRegistrar permissionKeyRegistrar;

    final UUID tenant = UUID.randomUUID();

    private RequestPostProcessor admin() {
        return jwt().jwt(j -> j.claim("admin", true).claim("tenantId", tenant.toString()));
    }

    private static String sfx() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, int expected)
            throws Exception {
        String body = mvc.perform(b.with(admin())).andExpect(status().is(expected)).andReturn().getResponse()
                .getContentAsString();
        return body.isBlank() ? null : json.readTree(body);
    }

    private JsonNode createTitle(String name, String extra) throws Exception {
        return call(post("/internal/books").contentType(MediaType.APPLICATION_JSON).content(
                "{\"name\":\"" + name + "\",\"author\":\"Rowling\",\"isbn\":\"978-" + sfx() + "\",\"coverPrice\":1000" + extra
                        + "}"), 201);
    }

    @Test
    void taoKhongWorkIdTuTaoWork1Va1VaSuaTruongCapTacPhamDiVaoWork() throws Exception {
        String s = sfx();
        JsonNode t = createTitle("HP " + s, ",\"series\":\"Bộ " + s + "\",\"seriesNumber\":1,\"language\":\"vi\"");
        String workId = t.get("workId").asText();
        assertThat(workId).isNotBlank();
        assertThat(t.get("workTitle").asText()).isEqualTo("HP " + s);
        assertThat(t.get("seriesName").asText()).isEqualTo("Bộ " + s);
        assertThat(t.get("series").asText()).isEqualTo("Bộ " + s);
        assertThat(t.get("seriesNumber").asInt()).isEqualTo(1);

        // ấn bản thứ hai gắn vào cùng tác phẩm (tiếng Anh) kèm nhãn phần
        JsonNode t2 = createTitle("HP EN " + s, ",\"workId\":\"" + workId + "\",\"language\":\"en\",\"partNumber\":2,\"partLabel\":\"Phần 2\"");
        assertThat(t2.get("workId").asText()).isEqualTo(workId);
        assertThat(t2.get("partNumber").asInt()).isEqualTo(2);
        assertThat(t2.get("series").asText()).isEqualTo("Bộ " + s); // phủ từ work
        call(post("/internal/books").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"author\":\"y\",\"coverPrice\":1,\"workId\":\"" + UUID.randomUUID() + "\"}"), 404);

        // sửa thể loại/bộ sách qua ấn bản 2 đổi cho cả tác phẩm
        String id2 = t2.get("id").asText();
        call(put("/internal/books/" + id2).contentType(MediaType.APPLICATION_JSON).content(
                "{\"name\":\"HP EN " + s + "\",\"author\":\"Rowling\",\"coverPrice\":1000,\"language\":\"en\","
                        + "\"series\":\"Bộ mới " + s + "\",\"seriesNumber\":3}"), 200);
        JsonNode first = call(get("/internal/books/" + t.get("id").asText()), 200);
        assertThat(first.get("series").asText()).isEqualTo("Bộ mới " + s);
        assertThat(first.get("seriesNumber").asInt()).isEqualTo(3);

        JsonNode detail = call(get("/internal/works/" + workId), 200);
        assertThat(detail.get("editions")).hasSize(2);
        assertThat(detail.get("seriesName").asText()).isEqualTo("Bộ mới " + s);
        call(get("/internal/works/" + UUID.randomUUID()), 404);

        JsonNode page = call(get("/internal/works").param("keyword", "HP " + s), 200);
        assertThat(page.get("total").asLong()).isEqualTo(1);
        assertThat(page.get("items").get(0).get("editionCount").asLong()).isEqualTo(2);
        JsonNode bySeries = call(get("/internal/works").param("seriesId", detail.get("seriesId").asText()), 200);
        assertThat(bySeries.get("total").asLong()).isEqualTo(1);
        JsonNode books = call(get("/internal/books").param("workId", workId), 200);
        assertThat(books).hasSize(2);
    }

    @Test
    void gopVaTachTacPham() throws Exception {
        String s = sfx();
        JsonNode a = createTitle("A " + s, "");
        JsonNode b = createTitle("B " + s, "");
        String wa = a.get("workId").asText();
        String wb = b.get("workId").asText();
        call(post("/internal/works/" + wa + "/merge").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceWorkIds\":[\"" + wa + "\"]}"), 409);
        JsonNode merged = call(post("/internal/works/" + wa + "/merge").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceWorkIds\":[\"" + wb + "\"]}"), 200);
        assertThat(merged.get("editions")).hasSize(2);
        call(get("/internal/works/" + wb), 404);
        assertThat(call(get("/internal/books/" + b.get("id").asText()), 200).get("workId").asText()).isEqualTo(wa);

        // tách ấn bản B ra tác phẩm mới
        JsonNode split = call(put("/internal/books/" + b.get("id").asText() + "/work")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"newWork\":{\"title\":\"Tách " + s + "\",\"author\":\"Rowling\"}}"), 200);
        assertThat(split.get("workId").asText()).isNotEqualTo(wa);
        assertThat(split.get("workTitle").asText()).isEqualTo("Tách " + s);
        call(put("/internal/books/" + b.get("id").asText() + "/work").contentType(MediaType.APPLICATION_JSON)
                .content("{}"), 400);
    }

    @Test
    void bieuDoCauSqlCuaDanhSachKhongTangTheoSoDong() throws Exception {
        String s = sfx();
        for (int i = 0; i < 12; i++) createTitle("N1 " + s + " " + i, ",\"series\":\"Bộ " + s + "\"");
        Statistics stats = sessionFactory.getStatistics();
        stats.clear();
        call(get("/internal/works").param("keyword", "N1 " + s).param("size", "3"), 200);
        long small = stats.getPrepareStatementCount();
        stats.clear();
        JsonNode big = call(get("/internal/works").param("keyword", "N1 " + s).param("size", "12"), 200);
        assertThat(big.get("items")).hasSize(12);
        assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(small + 1); // +1: không phụ thuộc số dòng
        stats.clear();
        call(get("/internal/books").param("keyword", "N1 " + s), 200);
        long books12 = stats.getPrepareStatementCount();
        assertThat(books12).isLessThan(20);
    }

    @Test
    void boSachCrud() throws Exception {
        String name = "Series " + sfx();
        JsonNode created = call(post("/internal/series").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}"), 201);
        call(post("/internal/series").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name.toUpperCase() + "\"}"), 409);
        call(put("/internal/series/" + created.get("id").asText() + "/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"), 200);
        mvc.perform(get("/internal/series/options").param("keyword", name).with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(0)));
        mvc.perform(get("/internal/series").param("keyword", name).with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)));
    }
}

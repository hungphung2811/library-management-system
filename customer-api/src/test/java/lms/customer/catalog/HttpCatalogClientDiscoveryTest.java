package lms.customer.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lms.customer.catalog.client.http.HttpCatalogClient;
import lms.customer.catalog.dto.BookDtos.BookSummaryView;
import lms.customer.catalog.dto.BookDtos.SuggestionView;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** ADR-0019: sort/inStock/suggestions gửi sang sales và bộ trường DTO công khai. */
class HttpCatalogClientDiscoveryTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://sales");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpCatalogClient client = new HttpCatalogClient(builder.build());
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void listGuiSortVaInStockKhiCo() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://sales/internal/sales/storefront/items")))
                .andExpect(queryParam("sort", "price_asc")).andExpect(queryParam("inStock", "true"))
                .andRespond(withSuccess("{\"items\":[],\"total\":0,\"page\":0,\"size\":10}", MediaType.APPLICATION_JSON));
        client.list("t", null, "price_asc", true, 0, 10);
        server.verify();
    }

    @Test
    void listKhongGuiSortVaInStockKhiVang() {
        server.expect(requestTo("http://sales/internal/sales/storefront/items?page=0&size=10"))
                .andRespond(withSuccess("{\"items\":[],\"total\":0,\"page\":0,\"size\":10}", MediaType.APPLICATION_JSON));
        client.list("t", null, null, false, 0, 10);
        server.verify();
    }

    @Test
    void goiYGuiTokenQVaLimitVaChiGiu3Truong() throws Exception {
        UUID id = UUID.randomUUID();
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://sales/internal/sales/storefront/suggestions")))
                .andExpect(header("Authorization", "Bearer svc"))
                .andExpect(queryParam("q", "nh%C3%A0%20%26%20x")).andExpect(queryParam("limit", "5"))
                .andRespond(withSuccess("[{\"titleId\":\"" + id + "\",\"name\":\"Nhà\",\"author\":\"A\","
                        + "\"price\":9,\"onHand\":4,\"isbn\":\"1\"}]", MediaType.APPLICATION_JSON));
        List<SuggestionView> out = client.suggestions("svc", "nhà & x", 5);
        assertThat(out).containsExactly(new SuggestionView(id, "Nhà", "A"));
        assertThat(json.writeValueAsString(out)).doesNotContain("price", "onHand", "isbn");
        server.verify();
    }

    @Test
    void tapTenTruongDtoDuocGiuNguyen() {
        assertThat(Arrays.stream(BookSummaryView.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("bookRef", "title", "author", "price", "inStock", "hasPreview", "subtitle", "publicationYear",
                        "edition", "language", "pageCount", "format", "translator", "series", "seriesNumber",
                        "coverImageUrl", "tags");
        assertThat(Arrays.stream(SuggestionView.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("bookRef", "title", "author");
    }

    @Test
    void listVaChiTietChuyenThongTinBoSungVaKhongLoNoiBo() throws Exception {
        UUID id = UUID.randomUUID();
        String extras = "\"subtitle\":\"Phụ đề\",\"publicationYear\":2019,\"edition\":\"Tái bản lần thứ 3\","
                + "\"language\":\"vi\",\"pageCount\":320,\"format\":\"PAPERBACK\",\"translator\":\"Lý Lan\","
                + "\"series\":\"Bộ A\",\"seriesNumber\":2,\"coverImageUrl\":\"https://img.example/a.jpg\","
                + "\"tags\":[\"tiểu thuyết\",\"kinh điển\"]";
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://sales/internal/sales/storefront/items?")))
                .andRespond(withSuccess("{\"items\":[{\"titleId\":\"" + id + "\",\"name\":\"S\",\"author\":\"A\","
                        + "\"price\":9,\"inStock\":true,\"hasPreview\":false,\"onHand\":4,\"isbn\":\"1\"," + extras
                        + "}],\"total\":1,\"page\":0,\"size\":10}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/sales/storefront/items/" + id))
                .andRespond(withSuccess("{\"titleId\":\"" + id + "\",\"name\":\"S\",\"author\":\"A\",\"price\":9,"
                        + "\"inStock\":true,\"hasPreview\":false,\"description\":\"D\",\"tenantId\":\"x\"," + extras + "}",
                        MediaType.APPLICATION_JSON));
        var item = client.list("t", null, null, false, 0, 10).items().get(0);
        var detail = client.get("t", id);
        assertThat(item.language()).isEqualTo("vi");
        assertThat(item.tags()).containsExactly("tiểu thuyết", "kinh điển");
        assertThat(item.pageCount()).isEqualTo(320);
        assertThat(detail.translator()).isEqualTo("Lý Lan");
        assertThat(detail.seriesNumber()).isEqualTo(2);
        assertThat(detail.coverImageUrl()).isEqualTo("https://img.example/a.jpg");
        assertThat(json.writeValueAsString(item)).doesNotContain("onHand", "isbn", "\"price\":9,\"onHand");
        assertThat(json.writeValueAsString(detail)).doesNotContain("tenantId");
        server.verify();
    }

    @Test
    void thieuThongTinBoSungThiTagsRong() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo("http://sales/internal/sales/storefront/items/" + id))
                .andRespond(withSuccess("{\"titleId\":\"" + id + "\",\"name\":\"S\",\"author\":\"A\",\"price\":9,"
                        + "\"inStock\":true,\"hasPreview\":false}", MediaType.APPLICATION_JSON));
        var detail = client.get("t", id);
        assertThat(detail.tags()).isEmpty();
        assertThat(detail.subtitle()).isNull();
    }
}

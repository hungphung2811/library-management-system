package lms.customer.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import lms.customer.catalog.client.http.HttpCatalogClient;
import lms.customer.catalog.dto.BookDtos.BookSummaryView;
import lms.customer.web.PublicApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Stub sales items/by-ids: token dịch vụ, tham số ids, giữ thứ tự, trường thừa của sales không lọt ra ngoài. */
class HttpCatalogClientByIdsTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://sales");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpCatalogClient client = new HttpCatalogClient(builder.build());
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void guiTokenDichVuVaIdsGiuThuTuVaLocTruongThua() throws Exception {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://sales/internal/sales/storefront/items/by-ids")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andExpect(queryParam("ids", a + "," + b))
                .andRespond(withSuccess("[{\"titleId\":\"" + b + "\",\"name\":\"B\",\"author\":\"TG\",\"price\":2000,"
                        + "\"inStock\":true,\"hasPreview\":false,\"onHand\":42,\"isbn\":\"1\",\"tenantId\":\"t\"},"
                        + "{\"titleId\":\"" + a + "\",\"name\":\"A\",\"author\":\"TG\",\"price\":1000,"
                        + "\"inStock\":false,\"hasPreview\":true}]", MediaType.APPLICATION_JSON));
        List<BookSummaryView> out = client.byIds("svc-token", List.of(a, b));
        assertThat(out).extracting(BookSummaryView::bookRef).containsExactly(b, a);
        assertEquals("B", out.get(0).title());
        assertThat(json.writeValueAsString(out)).doesNotContain("onHand", "isbn", "tenantId");
        server.verify();
    }

    @Test
    void sales401ThanhLoiHaTang503() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://sales/internal/sales/storefront/items/by-ids")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertEquals(503, assertThrows(PublicApiException.class, () -> client.byIds("t", List.of(a))).status().value());
    }
}

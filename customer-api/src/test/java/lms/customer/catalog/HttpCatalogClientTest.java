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
import java.util.UUID;
import lms.customer.catalog.client.http.HttpCatalogClient;
import lms.customer.catalog.dto.BookDtos.BookDetailView;
import lms.customer.catalog.dto.BookDtos.BookPageView;
import lms.customer.catalog.dto.BookDtos.BookPreviewView;
import lms.customer.web.PublicApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Stub sales: token dịch vụ, tham số truy vấn, và trường nội bộ thừa của sales không bao giờ lọt vào DTO công khai. */
class HttpCatalogClientTest {

    private final UUID id = UUID.randomUUID();
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://sales");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpCatalogClient client = new HttpCatalogClient(builder.build());
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void danhSachGuiTokenDichVuVaThamSoVaLocTruongThua() throws Exception {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://sales/internal/sales/storefront/items")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andExpect(queryParam("page", "1")).andExpect(queryParam("size", "50"))
                .andExpect(queryParam("keyword", "c%2B%2B%20%26%20java"))
                .andRespond(withSuccess("{\"items\":[{\"titleId\":\"" + id + "\",\"name\":\"Sách\",\"author\":\"TG\","
                        + "\"price\":1000,\"inStock\":true,\"hasPreview\":false,\"onHand\":42,\"reserved\":3,"
                        + "\"available\":39,\"costPrice\":1,\"tenantId\":\"t\",\"isbn\":\"123\"}],"
                        + "\"total\":1,\"page\":1,\"size\":50,\"onHandTotal\":99}", MediaType.APPLICATION_JSON));
        BookPageView page = client.list("svc-token", "c++ & java", 1, 50);
        assertEquals(1, page.items().size());
        assertEquals(id, page.items().get(0).bookRef());
        String out = json.writeValueAsString(page);
        assertThat(out).doesNotContain("onHand", "reserved", "available", "costPrice", "tenantId", "isbn");
        server.verify();
    }

    @Test
    void chiTietVaPreviewLocTruongThua() throws Exception {
        server.expect(requestTo("http://sales/internal/sales/storefront/items/" + id))
                .andExpect(header("Authorization", "Bearer svc-token"))
                .andRespond(withSuccess("{\"titleId\":\"" + id + "\",\"name\":\"S\",\"author\":\"A\",\"price\":5,"
                        + "\"inStock\":false,\"hasPreview\":true,\"description\":\"Mô tả\",\"previewText\":\"KHONG\","
                        + "\"isbn\":\"9\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://sales/internal/sales/storefront/items/" + id + "/preview"))
                .andRespond(withSuccess("{\"titleId\":\"" + id + "\",\"name\":\"S\",\"previewText\":\"Đoạn\","
                        + "\"isbn\":\"9\"}", MediaType.APPLICATION_JSON));
        BookDetailView detail = client.get("svc-token", id);
        BookPreviewView preview = client.preview("svc-token", id);
        assertEquals("Mô tả", detail.description());
        assertEquals("Đoạn", preview.previewText());
        assertThat(json.writeValueAsString(detail)).doesNotContain("previewText", "KHONG", "isbn");
        assertThat(json.writeValueAsString(preview)).doesNotContain("isbn");
    }

    @Test
    void salesTuChoiTokenDichVuLaLoiHaTang503KhongPhaiCanDangNhap() {
        for (HttpStatus status : new HttpStatus[] {HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN}) {
            server.reset();
            server.expect(requestTo("http://sales/internal/sales/storefront/items/" + id))
                    .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON)
                            .body("{\"code\":\"FORBIDDEN\"}"));
            PublicApiException ex = assertThrows(PublicApiException.class, () -> client.get("svc-token", id));
            assertEquals(503, ex.status().value());
        }
    }

    @Test
    void moduleKhongBatVaLoiNghiepVuDuocNemNguyenDeBoLocLoi() {
        server.expect(requestTo("http://sales/internal/sales/storefront/items/" + id))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"MODULE_NOT_ENABLED\"}"));
        server.expect(requestTo("http://sales/internal/sales/storefront/items/" + id))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"STOCK_ITEM_NOT_FOUND\"}"));
        assertEquals(403, assertThrows(RestClientResponseException.class, () -> client.get("t", id)).getStatusCode().value());
        assertEquals(404, assertThrows(RestClientResponseException.class, () -> client.get("t", id)).getStatusCode().value());
    }

    @Test
    void thanRongLaLoiHaTang503() {
        server.expect(requestTo("http://sales/internal/sales/storefront/items/" + id))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        assertEquals(503, assertThrows(PublicApiException.class, () -> client.get("t", id)).status().value());
    }
}

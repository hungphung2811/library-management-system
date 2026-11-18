package lms.api.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import lms.api.catalog.client.http.HttpCatalogClient;
import lms.api.catalog.dto.AddCopyRequest;
import lms.api.catalog.dto.CopyStatus;
import lms.api.support.TokenRelayInterceptor;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Máy khách gọi đúng đường dẫn nội bộ /internal/**, ánh xạ DTO và chuyển tiếp token của người dùng. */
class HttpCatalogClientTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void goiApiNoiBoChuyenTiepTokenVaAnhXaDto() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog")
                .requestInterceptor(new TokenRelayInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpCatalogClient client = new HttpCatalogClient(builder.build());

        Jwt jwt = Jwt.withTokenValue("token-nguoi-dung").header("alg", "none").subject("u1").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        UUID title = UUID.randomUUID();
        server.expect(requestTo("http://catalog/internal/books?available=true&keyword=clean"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-nguoi-dung"))
                .andRespond(withSuccess("[{\"id\":\"" + title + "\",\"name\":\"Clean Code\",\"author\":\"Martin\","
                        + "\"isbn\":null,\"coverPrice\":250000.00,\"availableCopies\":3,\"campoMoi\":1}]",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://catalog/internal/book-copies"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.barcode").value("CC-1"))
                .andRespond(withSuccess("{\"id\":\"" + UUID.randomUUID() + "\",\"titleId\":\"" + title
                        + "\",\"barcode\":\"CC-1\",\"status\":\"AVAILABLE\"}", MediaType.APPLICATION_JSON));

        var titles = client.searchTitles("clean", true, null);
        assertEquals(1, titles.size());
        assertEquals(3, titles.get(0).availableCopies());
        assertEquals("AVAILABLE", client.addCopy(new AddCopyRequest(title, "CC-1")).status());
        server.verify();
    }

    @Test
    void suaDauSachGoiPutNoiBoVoiBodyDayDu() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog")
                .requestInterceptor(new TokenRelayInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpCatalogClient client = new HttpCatalogClient(builder.build());

        UUID title = UUID.randomUUID();
        server.expect(requestTo("http://catalog/internal/books/" + title))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.name").value("Clean Code 2"))
                .andExpect(jsonPath("$.coverPrice").value(300000))
                .andRespond(withSuccess("{\"id\":\"" + title + "\",\"name\":\"Clean Code 2\",\"author\":\"Martin\","
                        + "\"isbn\":\"978\",\"coverPrice\":300000,\"availableCopies\":2}", MediaType.APPLICATION_JSON));

        var view = client.updateTitle(title, new lms.api.catalog.dto.UpdateTitleRequest(
                "Clean Code 2", "Martin", "978", new java.math.BigDecimal("300000")));
        assertEquals("Clean Code 2", view.name());
        assertEquals(2, view.availableCopies());
        server.verify();
    }

    @Test
    void previewGoiGetVaPutNoiBoVaAnhXaDto() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog")
                .requestInterceptor(new TokenRelayInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpCatalogClient client = new HttpCatalogClient(builder.build());

        UUID title = UUID.randomUUID();
        server.expect(requestTo("http://catalog/internal/books/" + title + "/preview"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"" + title + "\",\"name\":\"A\",\"description\":\"d\","
                        + "\"previewText\":null,\"isbn\":\"978\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://catalog/internal/books/" + title + "/preview"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.description").value("d2"))
                .andExpect(jsonPath("$.previewText").value("p2"))
                .andRespond(withSuccess("{\"id\":\"" + title + "\",\"name\":\"A\",\"description\":\"d2\","
                        + "\"previewText\":\"p2\"}", MediaType.APPLICATION_JSON));

        var got = client.getPreview(title);
        assertEquals("d", got.description());
        assertEquals(null, got.previewText());
        assertEquals("p2", client.updatePreview(title,
                new lms.api.catalog.dto.UpdateTitlePreviewRequest("d2", "p2")).previewText());
        server.verify();
    }

    @Test
    void traBanSaoTheoIdMaVachVaDoiTrangThaiGoiDungDuongDanNoiBo() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog")
                .requestInterceptor(new TokenRelayInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpCatalogClient client = new HttpCatalogClient(builder.build());

        UUID copy = UUID.randomUUID();
        UUID title = UUID.randomUUID();
        String details = "{\"id\":\"" + copy + "\",\"titleId\":\"" + title + "\",\"titleName\":\"Clean Code\","
                + "\"barcode\":\"CC-1\",\"status\":\"LOANED\",\"coverPrice\":250000.00}";
        server.expect(requestTo("http://catalog/internal/book-copies/" + copy))
                .andExpect(method(HttpMethod.GET)).andRespond(withSuccess(details, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://catalog/internal/book-copies/by-barcode/CC-1"))
                .andExpect(method(HttpMethod.GET)).andRespond(withSuccess(details, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://catalog/internal/book-copies/" + copy + "/status"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.status").value("DAMAGED"))
                .andRespond(withSuccess("{\"id\":\"" + copy + "\",\"titleId\":\"" + title
                        + "\",\"barcode\":\"CC-1\",\"status\":\"DAMAGED\"}", MediaType.APPLICATION_JSON));

        assertEquals("Clean Code", client.getCopy(copy).titleName());
        assertEquals("CC-1", client.getCopyByBarcode("CC-1").barcode());
        assertEquals("DAMAGED", client.changeCopyStatus(copy, CopyStatus.DAMAGED).status());
        server.verify();
    }

    @Test
    void anhXaThongTinBoSungTuCatalogVaGuiNguyenTruongMoiKhiTao() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog")
                .requestInterceptor(new TokenRelayInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpCatalogClient client = new HttpCatalogClient(builder.build());

        UUID title = UUID.randomUUID();
        server.expect(requestTo("http://catalog/internal/books"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.language").value("vi"))
                .andExpect(jsonPath("$.format").value("HARDCOVER"))
                .andExpect(jsonPath("$.tags[0]").value("a"))
                .andRespond(withSuccess("{\"id\":\"" + title + "\",\"name\":\"A\",\"author\":\"B\",\"coverPrice\":1,"
                        + "\"availableCopies\":0,\"subtitle\":\"S\",\"publicationYear\":2020,\"edition\":\"E\","
                        + "\"language\":\"vi\",\"pageCount\":99,\"format\":\"HARDCOVER\",\"translator\":\"T\","
                        + "\"series\":\"Sr\",\"seriesNumber\":3,\"coverImageUrl\":\"https://x.example/c.png\","
                        + "\"tags\":[\"a\"]}", MediaType.APPLICATION_JSON));

        var created = client.createTitle(new lms.api.catalog.dto.CreateTitleRequest("A", "B", null,
                java.math.BigDecimal.ONE, null, null, null, "S", 2020, "E", "vi", 99, "HARDCOVER", "T", "Sr", 3,
                "https://x.example/c.png", java.util.List.of("a")));
        assertEquals("S", created.subtitle());
        assertEquals(2020, created.publicationYear());
        assertEquals("HARDCOVER", created.format());
        assertEquals(3, created.seriesNumber());
        assertEquals(java.util.List.of("a"), created.tags());
        server.verify();
    }

    @Test
    void catalogKhongTraTruongMoiThiNullVaTagsRong() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://catalog");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpCatalogClient client = new HttpCatalogClient(builder.build());
        UUID title = UUID.randomUUID();
        server.expect(requestTo("http://catalog/internal/books/" + title))
                .andRespond(withSuccess("{\"id\":\"" + title + "\",\"name\":\"A\",\"author\":\"B\",\"coverPrice\":1,"
                        + "\"availableCopies\":0}", MediaType.APPLICATION_JSON));
        var t = client.getTitle(title);
        assertEquals(null, t.subtitle());
        assertEquals(java.util.List.of(), t.tags());
    }
}

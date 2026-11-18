package lms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.integration.catalog.client.http.HttpCatalogClient;
import lms.integration.catalog.dto.BookDtos.BookView;
import lms.integration.common.dto.PageView;
import lms.integration.config.DownstreamRestClients;
import lms.integration.inventory.client.http.HttpInventoryClient;
import lms.integration.inventory.dto.InventoryDtos.InventoryItemView;
import lms.integration.orders.client.http.HttpOrderClient;
import lms.integration.orders.dto.OrderDtos.OrderView;
import lms.integration.web.IntegrationApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Client HTTP tới catalog/sales (MockRestServiceServer): token integration của người gọi được chuyển nguyên (và chỉ
 * token đó), không có X-Tenant-Id hay tham số tenant, đường dẫn/thân đúng, DTO là allowlist (trường nội bộ của service
 * không lọt ra), và mọi lỗi hạ nguồn được chuẩn hóa không lộ chi tiết.
 */
class HttpDownstreamClientsTest {

    private static final String TOKEN = "eyJ.token-cua-nguoi-goi.sig";
    private static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    private MockRestServiceServer server;
    private RestClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = DownstreamRestClients.customize(RestClient.builder().baseUrl("http://downstream"));
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
        Jwt jwt = Jwt.withTokenValue(TOKEN).header("alg", "RS256").subject("app").claim("tenantId", TENANT.toString())
                .claim("scope", "books:read").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt));
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // --- catalog ---

    @Test
    void sachTheoIdChuyenTokenVaChiLayTruongAllowlist() throws Exception {
        UUID id = UUID.randomUUID();
        server.expect(requestTo("http://downstream/internal/books/" + id)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer " + TOKEN)).andExpect(headerDoesNotExist("X-Tenant-Id"))
                .andRespond(withSuccess("{\"id\":\"" + id + "\",\"name\":\"Clean Code\",\"author\":\"RM\","
                        + "\"isbn\":\"978\",\"coverPrice\":250000,\"availableCopies\":7,"
                        + "\"tenantId\":\"" + TENANT + "\",\"authors\":[{\"id\":\"" + UUID.randomUUID()
                        + "\",\"name\":\"Robert Martin\"}],\"tags\":[\"it\"],\"format\":\"PAPERBACK\","
                        + "\"truongNoiBoMoi\":\"x\"}", MediaType.APPLICATION_JSON));
        BookView view = new HttpCatalogClient(client).get(id);
        assertThat(view.name()).isEqualTo("Clean Code");
        assertThat(view.authors()).hasSize(1);
        assertThat(view.format()).isEqualTo("PAPERBACK");
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(view);
        assertThat(json).doesNotContain("tenantId").doesNotContain("availableCopies").doesNotContain("truongNoiBo")
                .doesNotContain(TENANT.toString());
        server.verify();
    }

    @Test
    void lookupGoiDungDuongDanVaThan() {
        UUID a = UUID.randomUUID();
        server.expect(requestTo("http://downstream/internal/books/lookup")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(jsonPath("$.ids[0]").value(a.toString()))
                .andRespond(withSuccess("[{\"id\":\"" + a + "\",\"name\":\"N\",\"description\":\"mo ta\","
                        + "\"hasPreview\":true,\"previewText\":\"x\"}]", MediaType.APPLICATION_JSON));
        List<BookView> rows = new HttpCatalogClient(client).lookup(List.of(a));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).description()).isEqualTo("mo ta");
        server.verify();
    }

    @Test
    void lookupTheoIsbnGoiDungDuongDanVaThan() {
        server.expect(requestTo("http://downstream/internal/books/lookup-by-isbn")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"isbns\":[\"9780132350884\"]}"))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertThat(new HttpCatalogClient(client).lookupByIsbn(List.of("9780132350884"))).isEmpty();
        server.verify();
    }

    // --- inventory ---

    @Test
    void tonKhoKhongLoGiaVonVaNhaCungCap() throws Exception {
        UUID titleId = UUID.randomUUID();
        server.expect(requestTo("http://downstream/internal/sales/stock-items?lowStockOnly=true&page=2&size=10"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(headerDoesNotExist("X-Tenant-Id"))
                .andRespond(withSuccess("{\"items\":[{\"titleId\":\"" + titleId + "\",\"titleName\":\"T\","
                        + "\"onHand\":5,\"reserved\":1,\"available\":4,\"sellingPrice\":100,\"effectivePrice\":100,"
                        + "\"lowStock\":true,\"active\":true,\"lowStockThreshold\":5,\"sku\":\"S1\","
                        + "\"supplier\":\"NCC-BI-MAT\",\"costPrice\":50,\"binLocation\":\"A1\",\"note\":\"noi bo\"}],"
                        + "\"total\":21,\"page\":2,\"size\":10}", MediaType.APPLICATION_JSON));
        PageView<InventoryItemView> page = new HttpInventoryClient(client).list(true, 2, 10);
        assertThat(page.total()).isEqualTo(21);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).available()).isEqualTo(4);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(page);
        assertThat(json).doesNotContain("NCC-BI-MAT").doesNotContain("costPrice").doesNotContain("noi bo")
                .doesNotContain("binLocation").doesNotContain("supplier");
        server.verify();
    }

    @Test
    void tonKhoChiTietTheoTitleId() {
        UUID titleId = UUID.randomUUID();
        server.expect(requestTo("http://downstream/internal/sales/stock-items/" + titleId))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withSuccess("{\"titleId\":\"" + titleId + "\",\"titleName\":\"T\",\"onHand\":1,"
                        + "\"reserved\":0,\"available\":1,\"lowStock\":false,\"active\":true}",
                        MediaType.APPLICATION_JSON));
        assertThat(new HttpInventoryClient(client).get(titleId).titleId()).isEqualTo(titleId);
        server.verify();
    }

    // --- orders ---

    @Test
    void donHangKhongLoLienHeVaGhiChu() throws Exception {
        UUID id = UUID.randomUUID();
        server.expect(requestTo("http://downstream/internal/sales/orders?page=0&size=20&status=CONFIRMED"))
                .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization", "Bearer " + TOKEN))
                .andExpect(queryParam("status", "CONFIRMED"))
                .andRespond(withSuccess("{\"items\":[" + upstreamOrder(id) + "],\"total\":1,\"page\":0,\"size\":20}",
                        MediaType.APPLICATION_JSON));
        PageView<OrderView> page = new HttpOrderClient(client).list("CONFIRMED", 0, 20);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).lines()).hasSize(1);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(page);
        assertThat(json).doesNotContain("Nguyen Van A").doesNotContain("0900000000").doesNotContain("Duong Noi Bo")
                .doesNotContain("ghi-chu-khach").doesNotContain("lyDoHuy").doesNotContain("customerUserId")
                .doesNotContain("contact").doesNotContain("recipient").doesNotContain("tenantId");
        server.verify();
    }

    @Test
    void donHangKhongLocTrangThaiKhongGuiThamSoStatus() {
        server.expect(requestTo("http://downstream/internal/sales/orders?page=1&size=5"))
                .andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withSuccess("{\"items\":[],\"total\":0,\"page\":1,\"size\":5}", MediaType.APPLICATION_JSON));
        assertThat(new HttpOrderClient(client).list(null, 1, 5).items()).isEmpty();
        server.verify();
    }

    @Test
    void donHangChiTietGoiDungDuongDanVaKhongBaoGioGoiContact() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo("http://downstream/internal/sales/orders/" + id)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(upstreamOrder(id), MediaType.APPLICATION_JSON));
        assertThat(new HttpOrderClient(client).get(id).orderNo()).isEqualTo("SO-1");
        server.verify();
    }

    private static String upstreamOrder(UUID id) {
        return "{\"id\":\"" + id + "\",\"orderNo\":\"SO-1\",\"channel\":\"ONLINE\",\"status\":\"CONFIRMED\","
                + "\"paymentStatus\":\"PAID\",\"paymentMethod\":\"COD\",\"fulfilment\":\"SHIPPING\","
                + "\"customerUserId\":\"" + UUID.randomUUID() + "\","
                + "\"contact\":{\"name\":\"Nguyen Van A\",\"phone\":\"0900000000\",\"address\":\"Duong Noi Bo\"},"
                + "\"vatRateBps\":800,\"pricesIncludeVat\":true,\"currency\":\"VND\",\"subtotal\":100,"
                + "\"discountTotal\":0,\"vatTotal\":8,\"shippingFee\":0,\"grandTotal\":100,"
                + "\"createdAt\":\"2026-01-01T00:00:00Z\",\"cancelReason\":\"lyDoHuy\",\"version\":3,"
                + "\"customerNote\":\"ghi-chu-khach\",\"tenantId\":\"" + TENANT + "\","
                + "\"lines\":[{\"id\":\"" + UUID.randomUUID() + "\",\"lineNo\":1,\"titleId\":\"" + UUID.randomUUID()
                + "\",\"titleName\":\"T\",\"isbn\":\"1\",\"quantity\":1,\"unitPrice\":100,\"lineDiscount\":0,"
                + "\"allocatedOrderDiscount\":0,\"amount\":100,\"vatAmount\":8,\"lineTotal\":100}]}";
    }

    // --- lỗi ---

    @Test
    void loiHaNguonDuocChuanHoaKhongLoThanLoi() {
        UUID id = UUID.randomUUID();
        HttpCatalogClient catalog = new HttpCatalogClient(client);
        String leak = "{\"code\":\"FORBIDDEN\",\"message\":\"Thiếu khóa TitleController/get\"}";
        record Case(HttpStatus upstream, HttpStatus expected, String code) { }
        for (Case c : List.of(new Case(HttpStatus.BAD_REQUEST, HttpStatus.BAD_REQUEST, "INVALID_REQUEST"),
                new Case(HttpStatus.UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"),
                new Case(HttpStatus.FORBIDDEN, HttpStatus.FORBIDDEN, "FORBIDDEN"),
                new Case(HttpStatus.NOT_FOUND, HttpStatus.NOT_FOUND, "NOT_FOUND"),
                new Case(HttpStatus.CONFLICT, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE"),
                new Case(HttpStatus.TOO_MANY_REQUESTS, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE"),
                new Case(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE"),
                new Case(HttpStatus.BAD_GATEWAY, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE"))) {
            server.reset();
            server.expect(requestTo("http://downstream/internal/books/" + id))
                    .andRespond(withStatus(c.upstream()).body(leak).contentType(MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> catalog.get(id)).isInstanceOfSatisfying(IntegrationApiException.class, e -> {
                assertThat(e.status()).isEqualTo(c.expected());
                assertThat(e.code()).isEqualTo(c.code());
                assertThat(e.getMessage()).doesNotContain("TitleController").doesNotContain("khóa");
            });
        }
    }

    @Test
    void matKetNoiVaThanLaThanh503() {
        UUID id = UUID.randomUUID();
        server.expect(requestTo("http://downstream/internal/books/" + id)).andRespond(request -> {
            throw new IOException("Connection refused: noi-bo:8082");
        });
        assertThatThrownBy(() -> new HttpCatalogClient(client).get(id)).isInstanceOfSatisfying(
                IntegrationApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getMessage()).doesNotContain("noi-bo");
                });
        server.reset();
        server.expect(requestTo("http://downstream/internal/books/" + id))
                .andRespond(withSuccess("khong phai json", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> new HttpCatalogClient(client).get(id)).isInstanceOfSatisfying(
                IntegrationApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void khongCoTokenTrongNguCanhThiKhongGoiHaNguon() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> new HttpCatalogClient(client).get(UUID.randomUUID()))
                .isInstanceOfSatisfying(IntegrationApiException.class,
                        e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
        server.verify();
    }
}

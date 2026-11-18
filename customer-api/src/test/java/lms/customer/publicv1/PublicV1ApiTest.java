package lms.customer.publicv1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lms.customer.auth.client.AuthClient;
import lms.customer.auth.dto.AuthDtos.IdentityTokenView;
import lms.customer.banner.client.BannerClient;
import lms.customer.banner.dto.BannerDtos.BannerView;
import lms.customer.catalog.client.CatalogClient;
import lms.customer.catalog.dto.BookDtos.BookDetailView;
import lms.customer.catalog.dto.BookDtos.BookPageView;
import lms.customer.catalog.dto.BookDtos.BookPreviewView;
import lms.customer.catalog.dto.BookDtos.SuggestionView;
import lms.customer.shop.client.ShopClient;
import lms.customer.shop.dto.ShopDtos.OrderLineView;
import lms.customer.shop.dto.ShopDtos.OrderPageView;
import lms.customer.shop.dto.ShopDtos.OrderView;
import lms.customer.shop.dto.ShopDtos.RecipientView;
import lms.customer.storefront.client.StorefrontAccessClient;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Public API v1 (Public Site Key, SaaS API Authentication P2), toàn bộ chuỗi filter + bộ đệm site key thật, identity và các
 * service phía sau là giả. T-P3: key sai/thiếu/ngưng cùng 404 SHOP_NOT_AVAILABLE cùng thân; CORS chỉ domain của Application và
 * không Allow-Credentials; phiên khách khác tenant 403; site key không vào log. Đường cũ {@code /public/t/{code}/**}
 * không đổi hành vi (xem các test hiện có và mục cuối).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.public-cors.mode=ENFORCE", "app.public-cors.platform-origins=https://saas.example.vn",
        "app.public-cors.dev-origins=http://localhost:5173"})
class PublicV1ApiTest {

    static final String KEY = "lms_pk_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345";
    static final String KEY_B = "lms_pk_BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB";
    static final String KEY_UNKNOWN = "lms_pk_UNKNOWNUNKNOWNUNKNOWNUNKNOWN1234";
    static final String SITE_ORIGIN = "https://shop.a.vn";
    static final String OTHER_APP_ORIGIN = "https://shop.b.vn";
    static final String LEGACY_TENANT_ORIGIN = "https://legacy.a.vn";

    // Cố định (static): bộ đệm site key của ngữ cảnh Spring dùng chung giữa các test nên giá trị phải ổn định.
    private static final UUID tenantA = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");
    private static final UUID tenantB = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000b");
    private static final UUID appA = UUID.fromString("aaaaaaaa-1111-0000-0000-00000000000a");
    private static final UUID appB = UUID.fromString("bbbbbbbb-1111-0000-0000-00000000000b");
    private final UUID bookId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @MockitoBean StorefrontAccessClient identity;
    @MockitoBean CatalogClient catalog;
    @MockitoBean ShopClient shop;
    @MockitoBean AuthClient auth;
    @MockitoBean BannerClient banners;
    @MockitoBean JwtDecoder jwtDecoder;

    @BeforeEach
    void identityGia() {
        // Application A của tenant A: origin hợp lệ chỉ SITE_ORIGIN (origin cấp tenant LEGACY không có ở đây: thuộc đường {code})
        when(identity.issueBySiteKey(KEY)).thenReturn(Optional.of(new StorefrontAccess(tenantA, "shop-a", "Hiệu sách A",
                "svc-token-a", 300, Set.of(SITE_ORIGIN), Set.of("SALES"), appA)));
        when(identity.issueBySiteKey(KEY_B)).thenReturn(Optional.of(new StorefrontAccess(tenantB, "shop-b", "Hiệu sách B",
                "svc-token-b", 300, Set.of(OTHER_APP_ORIGIN), Set.of("SALES"), appB)));
        when(identity.issueBySiteKey(KEY_UNKNOWN)).thenReturn(Optional.empty());
        // Đường cũ {code}: có origin cấp tenant
        when(identity.issue("shop-a")).thenReturn(Optional.of(new StorefrontAccess(tenantA, "shop-a", "Hiệu sách A",
                "svc-token-a", 300, Set.of(LEGACY_TENANT_ORIGIN, SITE_ORIGIN), Set.of("SALES"), null)));
    }

    private RequestPostProcessor customer(UUID tenant) {
        return jwt().jwt(j -> j.tokenValue("user-token").subject("u1").claim("tenantId", tenant.toString())
                .audience(List.of("lms-storefront")));
    }

    private OrderView order() {
        return new OrderView(orderId, "ON-1", "PENDING_CONFIRMATION", "UNPAID", "BANK_TRANSFER", "DELIVERY",
                new RecipientView("An", "0900000000", "1 Lê Lợi"), "VND", 100, 0, 0, 0, 100,
                Instant.parse("2026-11-17T01:00:00Z"), null, null, null, null,
                List.of(new OrderLineView(bookId, "Sách A", 1, 100, 100)), true);
    }

    private String place() {
        return """
                {"lines":[{"titleId":"%s","quantity":2}],"fulfilment":"DELIVERY","paymentMethod":"BANK_TRANSFER",
                 "recipient":{"name":"An","phone":"0900000000","address":"1 Lê Lợi"},
                 "clientRequestId":"%s"}""".formatted(bookId, UUID.randomUUID());
    }

    private static String stripTimestamp(String json) {
        return json.replaceAll("\"timestamp\"\\s*:\\s*\"[^\"]*\"", "");
    }

    // ---------- Sách và banner: 200 qua client giả ----------

    @Test
    void danhSachSachTheoSiteKeyDungTokenDichVuVaGioiHanSize50() throws Exception {
        when(catalog.list(anyString(), any(), any(), anyBoolean(), any(), anyInt(), anyInt()))
                .thenReturn(new BookPageView(List.of(), 0, 50, 0));

        mvc.perform(get("/api/public/v1/books?keyword=java&sort=newest&page=1&size=500").header("X-Site-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=30")));
        mvc.perform(head("/api/public/v1/books").header("X-Site-Key", KEY)).andExpect(status().isOk());

        verify(catalog).list("svc-token-a", "java", "newest", false, null, 1, 50);
    }

    @Test
    void thamSoSachSaiLa400VaKhongGoiSales() throws Exception {
        mvc.perform(get("/api/public/v1/books?sort=hack").header("X-Site-Key", KEY)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/v1/books?keyword=" + "a".repeat(101)).header("X-Site-Key", KEY))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/public/v1/books/suggestions?q=a").header("X-Site-Key", KEY)).andExpect(status().isBadRequest());
        verifyNoInteractions(catalog);
    }

    @Test
    void goiYChiTietVaXemThuSach() throws Exception {
        when(catalog.suggestions(eq("svc-token-a"), eq("ja"), anyInt()))
                .thenReturn(List.of(new SuggestionView(bookId, "Java", "A")));
        when(catalog.get(eq("svc-token-a"), eq(bookId))).thenReturn(new BookDetailView(bookId, "A", "B", 1, true, false, null));
        when(catalog.preview(eq("svc-token-a"), eq(bookId))).thenReturn(new BookPreviewView(bookId, "A", "văn bản"));

        mvc.perform(get("/api/public/v1/books/suggestions?q=ja").header("X-Site-Key", KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].title").value("Java"));
        mvc.perform(get("/api/public/v1/books/" + bookId).header("X-Site-Key", KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bookRef").value(bookId.toString()));
        mvc.perform(get("/api/public/v1/books/" + bookId + "/preview").header("X-Site-Key", KEY))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/v1/books/khong-phai-uuid").header("X-Site-Key", KEY))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BOOK_NOT_FOUND"));
    }

    @Test
    void banner() throws Exception {
        when(banners.list("svc-token-a")).thenReturn(List.of(new BannerView(UUID.randomUUID(), "T", "S", "/books", 1)));

        mvc.perform(get("/api/public/v1/banners").header("X-Site-Key", KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].title").value("T"));
    }

    // ---------- Phiên khách ----------

    @Test
    void dangNhapLamMoiDangXuatDangKyTheoSiteKey() throws Exception {
        when(auth.login("shop-a", "an", "mat-khau-1")).thenReturn(new IdentityTokenView("acc", "ref", 900));
        when(auth.refresh("ref", tenantA)).thenReturn(new IdentityTokenView("acc2", "ref2", 900));

        mvc.perform(post("/api/public/v1/sessions").header("X-Site-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"an\",\"password\":\"mat-khau-1\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.accessToken").value("acc")).andExpect(jsonPath("$.refreshToken").value("ref"));
        mvc.perform(post("/api/public/v1/sessions/refresh").header("X-Site-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"ref\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").value("acc2"));
        mvc.perform(post("/api/public/v1/sessions/logout").header("X-Site-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"ref\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/public/v1/sessions/signup").header("X-Site-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"an123\",\"password\":\"mat-khau-1\",\"fullName\":\"An\",\"email\":\"an@x.vn\"}"))
                .andExpect(status().isCreated());

        verify(auth).logout("ref");
        verify(auth).signup(eq("shop-a"), any());
    }

    // ---------- Đơn hàng: bắt buộc phiên khách ----------

    @Test
    void donHangCanPhienKhachVaChuyenTiepTokenKhach() throws Exception {
        when(shop.placeOrder(eq("user-token"), any())).thenReturn(order());
        when(shop.myOrders("user-token", 0, 20)).thenReturn(new OrderPageView(List.of(), 0, 20, 0));
        when(shop.myOrder("user-token", orderId)).thenReturn(order());
        when(shop.cancelMyOrder("user-token", orderId, null)).thenReturn(order());

        mvc.perform(post("/api/public/v1/orders").with(customer(tenantA)).header("X-Site-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(place()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/public/v1/orders/" + orderId))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(get("/api/public/v1/orders").with(customer(tenantA)).header("X-Site-Key", KEY))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/v1/orders/" + orderId).with(customer(tenantA)).header("X-Site-Key", KEY))
                .andExpect(status().isOk());
        mvc.perform(post("/api/public/v1/orders/" + orderId + "/cancellation").with(customer(tenantA))
                        .header("X-Site-Key", KEY))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/v1/orders/khong-uuid").with(customer(tenantA)).header("X-Site-Key", KEY))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }

    @Test
    void donHangKhongTokenLa401KhongGoiSales() throws Exception {
        mvc.perform(post("/api/public/v1/orders").header("X-Site-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(place()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/public/v1/orders").header("X-Site-Key", KEY)).andExpect(status().isUnauthorized());
        verifyNoInteractions(shop);
    }

    @Test
    void phienKhachKhacTenantCuaSiteKeyLa403SessionMismatch() throws Exception {
        for (var request : List.of(
                post("/api/public/v1/orders").content(place()).contentType(MediaType.APPLICATION_JSON),
                get("/api/public/v1/orders"), get("/api/public/v1/orders/" + orderId),
                post("/api/public/v1/orders/" + orderId + "/cancellation"))) {
            mvc.perform(request.with(customer(tenantB)).header("X-Site-Key", KEY))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SHOP_SESSION_MISMATCH"));
        }
        // token không có tenantId cũng bị từ chối
        mvc.perform(get("/api/public/v1/orders").with(jwt().jwt(j -> j.tokenValue("t").subject("u")
                        .audience(List.of("lms-storefront")))).header("X-Site-Key", KEY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(shop);
    }

    @Test
    void tokenDichVuVaTokenKhongAudBi401() throws Exception {
        mvc.perform(get("/api/public/v1/orders").header("X-Site-Key", KEY)
                        .with(jwt().jwt(j -> j.tokenValue("svc").subject("s").claim("svc", "customer-api")
                                .claim("tenantId", tenantA.toString()).audience(List.of("lms-storefront")))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/public/v1/orders").header("X-Site-Key", KEY)
                        .with(jwt().jwt(j -> j.tokenValue("staff").subject("s").claim("tenantId", tenantA.toString()))))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(shop);
    }

    @Test
    void thanDatHangThieuClientRequestIdLa400() throws Exception {
        mvc.perform(post("/api/public/v1/orders").with(customer(tenantA)).header("X-Site-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"titleId\":\"" + bookId + "\",\"quantity\":1}],\"fulfilment\":\"DELIVERY\","
                                + "\"paymentMethod\":\"BANK_TRANSFER\",\"recipient\":{\"name\":\"An\",\"phone\":\"0900000000\","
                                + "\"address\":\"1 Lê Lợi\"}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        verifyNoInteractions(shop);
    }

    @Test
    void datHangSepayBi400PaymentNotEnabledKhongGoiSales() throws Exception {
        mvc.perform(post("/api/public/v1/orders").with(customer(tenantA)).header("X-Site-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(place().replace("BANK_TRANSFER", "SEPAY")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAYMENT_NOT_ENABLED"));
        verifyNoInteractions(shop);
    }

    // ---------- T-P3: key thiếu/sai/ngưng cùng 404 cùng thân ----------

    @Test
    void keyThieuSaiKhongTonTaiCungMotThanOMoiEndpointVaKhongGoiServiceNao() throws Exception {
        String expected = null;
        String[] keys = {null, "", "lms_pk_short", "lms_sk_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345", KEY_UNKNOWN, KEY + "x"};
        for (String key : keys) {
            for (var request : List.of(get("/api/public/v1/books"), get("/api/public/v1/books/suggestions?q=java"),
                    get("/api/public/v1/books/" + bookId), get("/api/public/v1/books/" + bookId + "/preview"),
                    get("/api/public/v1/banners"),
                    post("/api/public/v1/sessions").content("{\"username\":\"an\",\"password\":\"mat-khau-1\"}")
                            .contentType(MediaType.APPLICATION_JSON),
                    post("/api/public/v1/sessions/refresh").content("{\"refreshToken\":\"r\"}")
                            .contentType(MediaType.APPLICATION_JSON),
                    post("/api/public/v1/sessions/logout").content("{\"refreshToken\":\"r\"}")
                            .contentType(MediaType.APPLICATION_JSON),
                    post("/api/public/v1/sessions/signup").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"an123\",\"password\":\"mat-khau-1\",\"fullName\":\"An\",\"email\":\"an@x.vn\"}"),
                    get("/api/public/v1/orders").with(customer(tenantA)),
                    post("/api/public/v1/orders").with(customer(tenantA)).content(place())
                            .contentType(MediaType.APPLICATION_JSON))) {
                var builder = request;
                if (key != null) {
                    builder = builder.header("X-Site-Key", key);
                }
                String body = mvc.perform(builder).andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"))
                        .andReturn().getResponse().getContentAsString();
                if (expected == null) {
                    expected = stripTimestamp(body);
                }
                assertThat(stripTimestamp(body)).isEqualTo(expected);
            }
        }
        verifyNoInteractions(catalog, shop, auth, banners);
    }

    @Test
    void keySaiDinhDangKhongGoiIdentityVaKeyKhongTonTaiDuocDemPhuDinh() throws Exception {
        // khóa ngẫu nhiên mỗi lần chạy: bộ đệm phủ định của ngữ cảnh dùng chung có thể đã nhớ KEY_UNKNOWN từ test khác
        String unknown = "lms_pk_" + UUID.randomUUID().toString().replace("-", "");
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/public/v1/books").header("X-Site-Key", "lms_pk_short")).andExpect(status().isNotFound());
            mvc.perform(get("/api/public/v1/books").header("X-Site-Key", unknown)).andExpect(status().isNotFound());
        }
        verify(identity, never()).issueBySiteKey("lms_pk_short");
        verify(identity, times(1)).issueBySiteKey(unknown);
    }

    @Test
    void applicationTamNgungHoacTenantKhongKhaDungLaKetQuaRongCuaIdentityVaCung404() throws Exception {
        // identity trả rỗng cho application tạm ngưng/tenant ngưng/tắt module: customer-api không phân biệt
        String suspended = "lms_pk_SUSPENDEDSUSPENDEDSUSPENDED12345";
        when(identity.issueBySiteKey(suspended)).thenReturn(Optional.empty());

        String a = mvc.perform(get("/api/public/v1/books").header("X-Site-Key", suspended)).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String b = mvc.perform(get("/api/public/v1/books")).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(stripTimestamp(a)).isEqualTo(stripTimestamp(b));
    }

    @Test
    void tenantKhongBatSalesLa404SauKhiPhanGiai() throws Exception {
        String lendingOnly = "lms_pk_LENDINGLENDINGLENDINGLENDING1234";
        when(identity.issueBySiteKey(lendingOnly)).thenReturn(Optional.of(new StorefrontAccess(tenantA, "shop-a", "A", "t",
                300, Set.of(), Set.of("LENDING"), appA)));

        mvc.perform(get("/api/public/v1/books").header("X-Site-Key", lendingOnly))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"));
        verifyNoInteractions(catalog);
    }

    @Test
    void identityLoiLa503Chung() throws Exception {
        String down = "lms_pk_DOWNDOWNDOWNDOWNDOWNDOWNDOWN1234";
        when(identity.issueBySiteKey(down)).thenThrow(lms.customer.web.PublicApiException.serviceUnavailable());

        mvc.perform(get("/api/public/v1/books").header("X-Site-Key", down))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void duongDanNgoaiDanhSachDongBiTuChoi() throws Exception {
        // Q4 (không giỏ hàng server) và Q12 (không /categories): không có route, danh sách đóng chặn
        for (String path : List.of("/api/public/v1/cart", "/api/public/v1/categories", "/api/public/v1/books/x/y/z",
                "/api/public/v1/search", "/api/public/v2/books")) {
            mvc.perform(get(path).header("X-Site-Key", KEY)).andExpect(status().is4xxClientError());
        }
        mvc.perform(post("/api/public/v1/books").header("X-Site-Key", KEY).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is4xxClientError());
        verifyNoInteractions(catalog, shop);
    }

    @Test
    void thanQua16KbBi413TaiDuongDanMoi() throws Exception {
        String big = "{\"username\":\"" + "a".repeat(17 * 1024) + "\"}";
        mvc.perform(post("/api/public/v1/sessions").header("X-Site-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        verifyNoInteractions(auth);
    }

    // ---------- CORS ----------

    @Test
    void corsChiChoOriginCuaApplicationVaKhongBaoGioAllowCredentials() throws Exception {
        when(catalog.list(anyString(), any(), any(), anyBoolean(), any(), anyInt(), anyInt()))
                .thenReturn(new BookPageView(List.of(), 0, 20, 0));

        mvc.perform(get("/api/public/v1/books").header("X-Site-Key", KEY).header("Origin", SITE_ORIGIN))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", SITE_ORIGIN))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"))
                .andExpect(header().stringValues("Vary", org.hamcrest.Matchers.hasItems("Origin", "X-Site-Key")));

        // origin của Application khác, origin cấp tenant ({code}) và origin nền tảng/dev đều KHÔNG được phép với site key này
        for (String origin : List.of(OTHER_APP_ORIGIN, LEGACY_TENANT_ORIGIN, "https://saas.example.vn", "http://localhost:5173",
                "https://evil.test", "null")) {
            mvc.perform(get("/api/public/v1/books").header("X-Site-Key", KEY).header("Origin", origin))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        }
    }

    @Test
    void corsLoiVanMangHeaderKhiOriginDuocPhepVaKhongCoHeaderKhiKeySai() throws Exception {
        mvc.perform(get("/api/public/v1/orders").header("X-Site-Key", KEY).header("Origin", SITE_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", SITE_ORIGIN));
        // key sai/thiếu: 404 và không có Allow-Origin (không lộ gì, kể cả origin đúng của một Application)
        mvc.perform(get("/api/public/v1/books").header("X-Site-Key", KEY_UNKNOWN).header("Origin", SITE_ORIGIN))
                .andExpect(status().isNotFound()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(get("/api/public/v1/books").header("Origin", SITE_ORIGIN))
                .andExpect(status().isNotFound()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        // origin đúng của Application A nhưng key của Application B
        mvc.perform(get("/api/public/v1/books").header("X-Site-Key", KEY_B).header("Origin", SITE_ORIGIN))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void preflightLa204KhongToiControllerKhongCredentialsVaChoPhepHeaderSiteKey() throws Exception {
        for (String origin : List.of(SITE_ORIGIN, "https://evil.test")) {
            ResultActions r = mvc.perform(options("/api/public/v1/orders").header("Origin", origin)
                    .header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "authorization,content-type,x-site-key"));
            r.andExpect(status().isNoContent())
                    .andExpect(header().string("Access-Control-Allow-Methods", "GET, POST, OPTIONS"))
                    .andExpect(header().string("Access-Control-Allow-Headers",
                            org.hamcrest.Matchers.containsString("X-Site-Key")))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        }
        // preflight không mang X-Site-Key nên không thể quyết theo Application: phản chiếu origin hợp lệ, quyết thật ở request chính
        mvc.perform(options("/api/public/v1/books").header("Origin", SITE_ORIGIN)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Access-Control-Allow-Origin", SITE_ORIGIN));
        // không Origin: không có header CORS nào
        mvc.perform(options("/api/public/v1/books")).andExpect(status().isNoContent())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        verifyNoInteractions(catalog, shop, auth);
        verify(identity, never()).issueBySiteKey(any());
    }

    // ---------- Site key không vào log ----------

    @Test
    void siteKeyKhongXuatHienTrongLogCuaYeuCau() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            mvc.perform(get("/api/public/v1/books").header("X-Site-Key", KEY_UNKNOWN).header("Origin", SITE_ORIGIN));
            mvc.perform(get("/api/public/v1/books").header("X-Site-Key", KEY).header("Origin", "https://evil.test"));
            mvc.perform(post("/api/public/v1/sessions").header("X-Site-Key", KEY).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"an\",\"password\":\"mat-khau-bi-mat\"}"));
        } finally {
            root.detachAppender(appender);
        }
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains(KEY)
                || e.getFormattedMessage().contains(KEY_UNKNOWN) || e.getFormattedMessage().contains("mat-khau-bi-mat"));
    }

    // ---------- Đường cũ /public/t/{code}/** không đổi ----------

    @Test
    void duongCuTheoMaVanChayVaCorsCuKhongDoi() throws Exception {
        when(catalog.list(anyString(), any(), any(), anyBoolean(), any(), anyInt(), anyInt()))
                .thenReturn(new BookPageView(List.of(), 0, 20, 0));

        // origin cấp tenant và origin nền tảng được phép như trước ở đường {code}
        mvc.perform(get("/public/t/shop-a/books").header("Origin", LEGACY_TENANT_ORIGIN))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", LEGACY_TENANT_ORIGIN));
        mvc.perform(get("/public/t/shop-a/books").header("Origin", "https://saas.example.vn"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "https://saas.example.vn"));
        mvc.perform(get("/public/t/shop-a/books").header("Origin", "https://evil.test"))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(options("/public/t/shop-a/orders").header("Origin", "https://evil.test")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isNoContent()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        // header X-Site-Key không có tác dụng ở đường cũ
        mvc.perform(get("/public/t/shop-a/books").header("X-Site-Key", KEY_B)).andExpect(status().isOk());
        verify(catalog, times(4)).list(eq("svc-token-a"), any(), any(), anyBoolean(), any(), anyInt(), anyInt());
        verify(identity, never()).issueBySiteKey(any());
    }
}

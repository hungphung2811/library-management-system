package lms.customer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lms.customer.auth.client.AuthClient;
import lms.customer.auth.dto.AuthDtos.IdentityTokenView;
import lms.customer.auth.dto.AuthDtos.SignupRequest;
import lms.customer.auth.web.AuthController;
import lms.customer.catalog.client.CatalogClient;
import lms.customer.catalog.dto.BookDtos.BookDetailView;
import lms.customer.catalog.dto.BookDtos.BookPageView;
import lms.customer.catalog.dto.BookDtos.BookPreviewView;
import lms.customer.catalog.dto.BookDtos.BookSummaryView;
import lms.customer.catalog.web.BookController;
import lms.customer.config.SecurityConfiguration;
import lms.customer.shop.client.ShopClient;
import lms.customer.shop.dto.ShopDtos.OrderLineView;
import lms.customer.shop.dto.ShopDtos.OrderPageView;
import lms.customer.shop.dto.ShopDtos.OrderView;
import lms.customer.shop.dto.ShopDtos.PlaceOrderRequest;
import lms.customer.shop.dto.ShopDtos.RecipientView;
import lms.customer.shop.web.OrderController;
import lms.customer.site.client.SiteClient;
import lms.customer.site.web.SiteController;
import lms.customer.storefront.ShopSessionGuard;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.storefront.web.ShopController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Ma trận route công khai đóng, route cần đăng nhập (aud, svc, SHOP_SESSION_MISMATCH), đăng ký/đăng nhập/làm mới theo cửa
 * hàng, danh mục sách (token dịch vụ, size <= 50, trường allowlist) và đơn hàng (token người dùng, body allowlist, lỗi
 * allowlist). ADR-0011 2.2, 2.4, 2.6, 2.9, 3.3; ADR-0013 2.4.
 */
@WebMvcTest({SiteController.class, ShopController.class, AuthController.class, BookController.class,
        OrderController.class})
@Import({SecurityConfiguration.class, PublicExceptionHandler.class, ShopSessionGuard.class})
class StorefrontRoutesWebTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID bookId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @MockitoBean SiteClient site;
    @MockitoBean AuthClient auth;
    @MockitoBean StorefrontAccessCache storefronts;
    @MockitoBean CatalogClient catalog;
    @MockitoBean ShopClient shop;
    @MockitoBean JwtDecoder jwtDecoder;

    @BeforeEach
    void shopA() {
        when(storefronts.resolve("shop-a")).thenReturn(
                new StorefrontAccess(tenantA, "shop-a", "Hiệu sách A", "svc-token-a", 300));
    }

    private RequestPostProcessor customer(UUID tenant) {
        return jwt().jwt(j -> j.tokenValue("user-token").subject("u1").claim("tenantId", tenant.toString())
                .audience(List.of("lms-storefront")));
    }

    private static HttpClientErrorException downstream(HttpStatus status, String body) {
        return HttpClientErrorException.create(status, "err", HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    private OrderView order(String status) {
        return new OrderView(orderId, "ON-1", status, "UNPAID", "BANK_TRANSFER", "DELIVERY",
                new RecipientView("An", "0900000000", "1 Lê Lợi"), "VND", 100, 0, 0, 0, 100, Instant.parse("2026-11-17T01:00:00Z"),
                null, null, null, null, List.of(new OrderLineView(bookId, "Sách A", 1, 100, 100)),
                "PENDING_CONFIRMATION".equals(status));
    }

    private static final String PLACE = """
            {"lines":[{"titleId":"%s","quantity":2}],"fulfilment":"DELIVERY","paymentMethod":"BANK_TRANSFER",
             "recipient":{"name":"An","phone":"0900000000","address":"1 Lê Lợi"},
             "clientRequestId":"%s"}""";

    private String place() {
        return PLACE.formatted(bookId, UUID.randomUUID());
    }

    // ---------- Ma trận route: danh sách đóng ----------

    @Test
    void routeCongKhaiKhongCanTokenVaBoQuaAuthorization() throws Exception {
        when(catalog.list(anyString(), any(), any(), anyBoolean(), anyInt(), anyInt())).thenReturn(new BookPageView(List.of(), 0, 20, 0));
        when(catalog.get(anyString(), any())).thenReturn(new BookDetailView(bookId, "A", "B", 1, true, false, null));
        when(catalog.preview(anyString(), any())).thenReturn(new BookPreviewView(bookId, "A", "văn bản"));
        String withBadToken = "Bearer rac.khong.hop.le";
        for (String path : List.of("/public/t/shop-a", "/public/t/shop-a/books", "/public/t/shop-a/books/" + bookId,
                "/public/t/shop-a/books/" + bookId + "/preview")) {
            mvc.perform(get(path)).andExpect(status().isOk());
            mvc.perform(head(path)).andExpect(status().isOk());
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, withBadToken)).andExpect(status().isOk());
        }
    }

    @Test
    void routeDonHangKhongTokenLa401KhongGoiSalesHayIdentity() throws Exception {
        mvc.perform(post("/public/t/shop-a/orders").contentType(MediaType.APPLICATION_JSON).content(place()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/public/t/shop-a/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/public/t/shop-a/orders/" + orderId)).andExpect(status().isUnauthorized());
        mvc.perform(post("/public/t/shop-a/orders/" + orderId + "/cancellation")).andExpect(status().isUnauthorized());
        verifyNoInteractions(shop, storefronts);
    }

    @Test
    void duongDanVaPhuongThucNgoaiDanhSachDongKhongMoDuoc() throws Exception {
        // Mẫu "/public/t/*/**" không tồn tại: biến thể lạ, phương thức lạ, đường dẫn con lạ đều bị chặn.
        for (String path : List.of("/public/t/shop-a/orders/" + orderId + "/cancel", "/public/t/shop-a/orders/a/b/c",
                "/public/t/shop-a/books/a/b/c", "/public/t/shop-a/contact-requests", "/public/t/shop-a/admin",
                "/public/t/shop-a/auth", "/public/t/shop-a/auth/switch-tenant", "/public/t/shop-a/orders/")) {
            MvcResult r = mvc.perform(get(path)).andReturn();
            assertThat(r.getResponse().getStatus()).as(path).isIn(401, 403, 404);
        }
        for (String path : List.of("/public/t/shop-a/books", "/public/t/shop-a/books/" + bookId,
                "/public/t/shop-a/orders", "/public/t/shop-a/auth/login")) {
            MvcResult r = mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
            assertThat(r.getResponse().getStatus()).as("PUT " + path).isBetween(400, 499);
        }
        MvcResult postBooks = mvc.perform(post("/public/t/shop-a/books").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andReturn();
        assertThat(postBooks.getResponse().getStatus()).isBetween(400, 499);
        verifyNoInteractions(catalog, shop);
    }

    // ---------- Token ở route cần đăng nhập ----------

    @Test
    void tokenThieuAudStorefrontBi401() throws Exception {
        RequestPostProcessor noAud = jwt().jwt(j -> j.tokenValue("t").claim("tenantId", tenantA.toString()));
        RequestPostProcessor staffAud = jwt().jwt(j -> j.tokenValue("t").claim("tenantId", tenantA.toString())
                .audience(List.of("lms-staff")));
        for (RequestPostProcessor token : List.of(noAud, staffAud)) {
            mvc.perform(get("/public/t/shop-a/orders").with(token)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        verifyNoInteractions(shop);
    }

    @Test
    void tokenDichVuSvcTuNgoaiVaoBi401() throws Exception {
        mvc.perform(get("/public/t/shop-a/orders").with(jwt().jwt(j -> j.tokenValue("svc").claim("svc", "customer-api")
                        .claim("tenantId", tenantA.toString()).audience(List.of("lms-storefront")))))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(shop);
    }

    @Test
    void tokenTenantXGoiCuaHangYLa403ShopSessionMismatch() throws Exception {
        mvc.perform(get("/public/t/shop-a/orders").with(customer(tenantB)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SHOP_SESSION_MISMATCH"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/public/t/shop-a/orders").with(customer(tenantB)).contentType(MediaType.APPLICATION_JSON)
                .content(place())).andExpect(status().isForbidden());
        verifyNoInteractions(shop);
    }

    @Test
    void cuaHangKhongKhaDungLaMotMaChungKeCaKhiDaDangNhap() throws Exception {
        when(storefronts.resolve("khong-co")).thenThrow(PublicApiException.shopNotAvailable());
        mvc.perform(get("/public/t/khong-co/orders").with(customer(tenantA)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"));
        verifyNoInteractions(shop);
    }

    // ---------- Đơn hàng ----------

    @Test
    void datHangChuyenTokenNguoiDungVaChiNhanTruongAllowlist() throws Exception {
        when(shop.placeOrder(eq("user-token"), any())).thenReturn(order("PENDING_CONFIRMATION"));
        // Trường lạ (giá, giảm giá, trạng thái, userId, tenant, kênh) phải bị bỏ qua, không bao giờ tới sales.
        String body = place().replaceFirst("\\{\"lines\"", "{\"price\":1,\"discountTotal\":99,\"status\":\"PAID\","
                + "\"customerUserId\":\"x\",\"tenantId\":\"" + tenantB + "\",\"channel\":\"POS\",\"shippingFee\":0,\"lines\"")
                .replace("\"quantity\":2", "\"quantity\":2,\"unitPrice\":1,\"discount\":50");
        mvc.perform(post("/public/t/shop-a/orders").with(customer(tenantA)).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Location", "/public/t/shop-a/orders/" + orderId))
                .andExpect(jsonPath("$.id").value(orderId.toString()))
                .andExpect(jsonPath("$.cancellable").value(true));
        ArgumentCaptor<PlaceOrderRequest> captor = ArgumentCaptor.forClass(PlaceOrderRequest.class);
        verify(shop).placeOrder(eq("user-token"), captor.capture());
        PlaceOrderRequest sent = captor.getValue();
        assertThat(sent.lines()).hasSize(1);
        assertThat(sent.lines().get(0).quantity()).isEqualTo(2);
        assertThat(PlaceOrderRequest.class.getRecordComponents()).extracting("name")
                .containsExactly("lines", "fulfilment", "paymentMethod", "recipient", "clientRequestId");
    }

    @Test
    void clientRequestIdBatBuocVaPhaiLaUuid() throws Exception {
        String missing = place().replaceAll(",\\s*\"clientRequestId\":\"[^\"]*\"", "");
        String notUuid = place().replaceAll("\"clientRequestId\":\"[^\"]*\"", "\"clientRequestId\":\"abc\"");
        for (String body : List.of(missing, notUuid)) {
            mvc.perform(post("/public/t/shop-a/orders").with(customer(tenantA)).contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        verifyNoInteractions(shop);
    }

    @Test
    void thanDatHangSaiBi400KhongGoiSales() throws Exception {
        for (String body : List.of(
                place().replace("\"quantity\":2", "\"quantity\":0"),
                place().replace("\"DELIVERY\"", "\"TELEPORT\""),
                place().replace("\"BANK_TRANSFER\"", "\"CARD\""),
                place().replace("\"lines\":[{\"titleId\":\"" + bookId + "\",\"quantity\":2}]", "\"lines\":[]"),
                place().replace("\"name\":\"An\"", "\"name\":\"\""),
                "{}")) {
            mvc.perform(post("/public/t/shop-a/orders").with(customer(tenantA)).contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(shop);
    }

    @Test
    void danhSachDonCuaToiChanSizeToiDa50() throws Exception {
        when(shop.myOrders(eq("user-token"), anyInt(), anyInt())).thenReturn(new OrderPageView(List.of(), 0, 50, 0));
        mvc.perform(get("/public/t/shop-a/orders?size=1000&page=2").with(customer(tenantA)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(shop).myOrders("user-token", 2, 50);
        mvc.perform(get("/public/t/shop-a/orders?size=-5&page=-1").with(customer(tenantA))).andExpect(status().isOk());
        verify(shop).myOrders("user-token", 0, 20);
    }

    @Test
    void chiTietVaHuyDonChuyenTokenNguoiDung() throws Exception {
        when(shop.myOrder("user-token", orderId)).thenReturn(order("PENDING_CONFIRMATION"));
        when(shop.cancelMyOrder("user-token", orderId, null)).thenReturn(order("CANCELLED"));
        mvc.perform(get("/public/t/shop-a/orders/" + orderId).with(customer(tenantA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancellable").value(true));
        mvc.perform(post("/public/t/shop-a/orders/" + orderId + "/cancellation").with(customer(tenantA)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellable").value(false));
        mvc.perform(post("/public/t/shop-a/orders/" + orderId + "/cancellation").with(customer(tenantA))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"đổi ý\"}")).andExpect(status().isOk());
        verify(shop).cancelMyOrder("user-token", orderId, "đổi ý");
    }

    @Test
    void orderIdKhongPhaiUuidLa404OrderNotFoundKhongGoiSales() throws Exception {
        mvc.perform(get("/public/t/shop-a/orders/abc").with(customer(tenantA)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        verify(shop, never()).myOrder(any(), any());
    }

    @Test
    void phanHoiDonKhongCoTruongCam() throws Exception {
        when(shop.myOrder("user-token", orderId)).thenReturn(order("PENDING_CONFIRMATION"));
        String json = mvc.perform(get("/public/t/shop-a/orders/" + orderId).with(customer(tenantA)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = JSON.readTree(json);
        Set<String> names = new TreeSet<>();
        root.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("id", "orderNo", "status", "paymentStatus", "paymentMethod",
                "fulfilment", "recipient", "currency", "subtotal", "discountTotal", "vatTotal", "shippingFee",
                "grandTotal", "createdAt", "confirmedAt", "paidAt", "completedAt", "cancelledAt", "lines", "cancellable");
        assertThat(json).doesNotContain("customerUserId", "version", "tenantId", "channel", "contact", "cancelReason",
                "isbn");
    }

    @Test
    void loiDonHangTheoDanhSachChoPhepVaKhongLoSoTonHayTenKhoa() throws Exception {
        when(shop.placeOrder(any(), any())).thenThrow(downstream(HttpStatus.CONFLICT,
                "{\"code\":\"INSUFFICIENT_STOCK\",\"message\":\"Không đủ tồn kho\",\"details\":[\"available=3\","
                        + "\"titleId=" + bookId + "\",\"onHand=99\"]}"));
        mvc.perform(post("/public/t/shop-a/orders").with(customer(tenantA)).contentType(MediaType.APPLICATION_JSON)
                        .content(place()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.details.length()").value(1))
                .andExpect(jsonPath("$.details[0]").value("titleId=" + bookId))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("available"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("onHand"))));

        String[][] cases = {
                {"409", "CLIENT_REQUEST_CONFLICT", "CLIENT_REQUEST_CONFLICT"},
                {"409", "TITLE_NOT_AVAILABLE", "TITLE_NOT_AVAILABLE"},
                {"409", "STOCK_ITEM_NOT_REGISTERED", "TITLE_NOT_AVAILABLE"},
                {"400", "INVALID_REQUEST", "VALIDATION_FAILED"},
                {"404", "ORDER_NOT_FOUND", "ORDER_NOT_FOUND"},
        };
        for (String[] c : cases) {
            org.mockito.Mockito.doThrow(downstream(HttpStatus.valueOf(Integer.parseInt(c[0])),
                    "{\"code\":\"" + c[1] + "\",\"message\":\"nội dung nội bộ id=7\",\"details\":[\"userId=1\"]}"))
                    .when(shop).placeOrder(any(), any());
            mvc.perform(post("/public/t/shop-a/orders").with(customer(tenantA)).contentType(MediaType.APPLICATION_JSON)
                            .content(place()))
                    .andExpect(status().is(Integer.parseInt(c[0])))
                    .andExpect(jsonPath("$.code").value(c[2]))
                    .andExpect(jsonPath("$.details.length()").value(0))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("nội bộ"))));
        }
    }

    @Test
    void thongDiep403KhongLoTenKhoa() throws Exception {
        when(shop.myOrders(any(), anyInt(), anyInt())).thenThrow(downstream(HttpStatus.FORBIDDEN,
                "{\"code\":\"FORBIDDEN\",\"message\":\"Thiếu quyền ShopController/myOrders\"}"));
        mvc.perform(get("/public/t/shop-a/orders").with(customer(tenantA)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Controller"))));
    }

    @Test
    void salesChetThanh503Chung() throws Exception {
        when(shop.myOrders(any(), anyInt(), anyInt())).thenThrow(downstream(HttpStatus.SERVICE_UNAVAILABLE,
                "{\"code\":\"CATALOG_UNAVAILABLE\"}"));
        mvc.perform(get("/public/t/shop-a/orders").with(customer(tenantA)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    // ---------- Danh mục sách ----------

    @Test
    void danhMucDungTokenDichVuKeCaKhiCoAuthorizationCuaKhach() throws Exception {
        when(catalog.list(eq("svc-token-a"), any(), any(), anyBoolean(), anyInt(), anyInt())).thenReturn(new BookPageView(
                List.of(new BookSummaryView(bookId, "Sách A", "Tác giả", 120_000, true, false)), 0, 50, 1));
        MvcResult r = mvc.perform(get("/public/t/shop-a/books?size=500").param("keyword", " java ")
                        .with(customer(tenantA)).header("Origin", "http://x.test"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=30, public"))
                .andExpect(jsonPath("$.items[0].bookRef").value(bookId.toString()))
                .andExpect(jsonPath("$.items[0].price").value(120000))
                .andExpect(jsonPath("$.items[0].inStock").value(true))
                .andReturn();
        verify(catalog).list("svc-token-a", "java", null, false, 0, 50);
        JsonNode item = JSON.readTree(r.getResponse().getContentAsString()).get("items").get(0);
        Set<String> names = new TreeSet<>();
        item.fieldNames().forEachRemaining(names::add);
        assertThat(names).containsExactlyInAnyOrder("bookRef", "title", "author", "price", "inStock", "hasPreview",
                "subtitle", "publicationYear", "edition", "language", "pageCount", "format", "translator", "series",
                "seriesNumber", "coverImageUrl", "tags");
        assertThat(r.getResponse().getContentAsString()).doesNotContain("onHand", "reserved", "available", "isbn",
                "tenantId", "costPrice");
    }

    @Test
    void chiTietCoDescriptionVaPreviewRiengBiet() throws Exception {
        when(catalog.get("svc-token-a", bookId)).thenReturn(new BookDetailView(bookId, "A", "B", 5, false, true, "Mô tả"));
        when(catalog.preview("svc-token-a", bookId)).thenReturn(new BookPreviewView(bookId, "A", "Đoạn đọc thử"));
        mvc.perform(get("/public/t/shop-a/books/" + bookId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("Mô tả"))
                .andExpect(jsonPath("$.previewText").doesNotExist())
                .andExpect(jsonPath("$.length()").value(18))
                .andExpect(jsonPath("$.tags").isEmpty());
        mvc.perform(get("/public/t/shop-a/books/" + bookId + "/preview"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=60, public"))
                .andExpect(jsonPath("$.previewText").value("Đoạn đọc thử"))
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void sachKhongCoLaBookNotFoundVaBookRefLaKhongGoiSales() throws Exception {
        when(catalog.get(anyString(), eq(bookId))).thenThrow(downstream(HttpStatus.NOT_FOUND,
                "{\"code\":\"STOCK_ITEM_NOT_FOUND\",\"message\":\"stock_items id=9\"}"));
        mvc.perform(get("/public/t/shop-a/books/" + bookId)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOK_NOT_FOUND"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("stock_items"))));
        mvc.perform(get("/public/t/shop-a/books/abc")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOK_NOT_FOUND"));
        mvc.perform(get("/public/t/shop-a/books/abc/preview")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOK_NOT_FOUND"));
    }

    @Test
    void tuKhoaQuaDaiHoacCoKyTuDieuKhienLa400() throws Exception {
        mvc.perform(get("/public/t/shop-a/books").param("keyword", "a".repeat(101))).andExpect(status().isBadRequest());
        mvc.perform(get("/public/t/shop-a/books").param("keyword", "a" + (char) 0 + "b")).andExpect(status().isBadRequest());
        verifyNoInteractions(catalog);
    }

    @Test
    void cuaHangKhongKhaDungThiDanhMucKhongGoiSales() throws Exception {
        when(storefronts.resolve("khong-co")).thenThrow(PublicApiException.shopNotAvailable());
        mvc.perform(get("/public/t/khong-co/books")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"));
        verifyNoInteractions(catalog);
    }

    // ---------- Đăng ký, đăng nhập, làm mới theo cửa hàng ----------

    private static final String SIGNUP = """
            {"username":"khach1","password":"mat-khau-1","fullName":"Nguyễn An","email":"an@example.vn"}""";

    @Test
    void dangKyLayTenantTuDuongDanVaBoQuaTruongLa() throws Exception {
        String body = SIGNUP.replace("}", ",\"tenantCode\":\"tenant-khac\",\"role\":\"ADMIN\",\"tenantId\":\"" + tenantB
                + "\",\"mustChangePassword\":false}");
        mvc.perform(post("/public/t/shop-a/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(""));
        ArgumentCaptor<SignupRequest> captor = ArgumentCaptor.forClass(SignupRequest.class);
        verify(auth).signup(eq("shop-a"), captor.capture());
        assertThat(captor.getValue().username()).isEqualTo("khach1");
        assertThat(SignupRequest.class.getRecordComponents()).extracting("name")
                .containsExactly("username", "password", "fullName", "email");
    }

    @Test
    void dangKyAnhXaLoiCuaIdentityKhongLoTenant() throws Exception {
        String[][] cases = {{"403", "SIGNUP_NOT_AVAILABLE"}, {"409", "DUPLICATE_USERNAME"}, {"400", "VALIDATION_FAILED"}};
        for (String[] c : cases) {
            org.mockito.Mockito.doThrow(downstream(HttpStatus.valueOf(Integer.parseInt(c[0])),
                    "{\"code\":\"" + c[1] + "\",\"message\":\"tenant shop-a role signup_default\"}"))
                    .when(auth).signup(any(), any());
            mvc.perform(post("/public/t/shop-a/auth/signup").contentType(MediaType.APPLICATION_JSON).content(SIGNUP))
                    .andExpect(status().is(Integer.parseInt(c[0])))
                    .andExpect(jsonPath("$.code").value(c[1]))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("signup_default"))));
        }
    }

    @Test
    void dangKyThanSaiLa400KhongGoiIdentityVaCuaHangLaKhongGoiIdentity() throws Exception {
        for (String body : List.of("{}", SIGNUP.replace("an@example.vn", "khong-phai-email"),
                SIGNUP.replace("mat-khau-1", "ngan"), SIGNUP.replace("khach1", "ab"))) {
            mvc.perform(post("/public/t/shop-a/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        when(storefronts.resolve("khong-co")).thenThrow(PublicApiException.shopNotAvailable());
        mvc.perform(post("/public/t/khong-co/auth/signup").contentType(MediaType.APPLICATION_JSON).content(SIGNUP))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"));
        verifyNoInteractions(auth);
    }

    @Test
    void refreshTheoCuaHangGuiTenantIdVaLogoutPhanGiaiCuaHang() throws Exception {
        when(auth.refresh("ref", tenantA)).thenReturn(new IdentityTokenView("acc2", "ref2", 900));
        mvc.perform(post("/public/t/shop-a/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"ref\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").value("acc2"))
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(post("/public/t/shop-a/auth/logout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"ref\"}"))
                .andExpect(status().isNoContent());
        verify(auth).logout("ref");
        // Đường cũ không có mã: không phân giải cửa hàng, refresh không kiểm tenant.
        when(auth.refresh("ref")).thenReturn(new IdentityTokenView("acc3", "ref3", 900));
        mvc.perform(post("/public/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"ref\"}")).andExpect(status().isOk());
    }

    @Test
    void refreshSaiTenantCuaIdentityLa401ChungVaCuaHangLaKhongGoiIdentity() throws Exception {
        when(auth.refresh("ref", tenantA)).thenThrow(downstream(HttpStatus.UNAUTHORIZED,
                "{\"code\":\"UNAUTHORIZED\",\"message\":\"token thuộc tenant khác\"}"));
        mvc.perform(post("/public/t/shop-a/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"ref\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("tenant khác"))));
        when(storefronts.resolve("khong-co")).thenThrow(PublicApiException.shopNotAvailable());
        mvc.perform(post("/public/t/khong-co/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"ref\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/public/t/khong-co/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"ref\"}")).andExpect(status().isNotFound());
        verify(auth, never()).refresh("ref", UUID.randomUUID());
    }
}

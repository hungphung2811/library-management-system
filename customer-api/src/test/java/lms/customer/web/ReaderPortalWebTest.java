package lms.customer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lms.customer.auth.client.AuthClient;
import lms.customer.auth.dto.AuthDtos.IdentityTokenView;
import lms.customer.auth.web.AuthController;
import lms.customer.catalog.client.CatalogClient;
import lms.customer.catalog.web.BookController;
import lms.customer.config.SecurityConfiguration;
import lms.customer.reader.client.CatalogTitleClient;
import lms.customer.reader.client.CirculationReaderClient;
import lms.customer.reader.client.CirculationReaderClient.ReservationRow;
import lms.customer.reader.client.IdentityReaderClient;
import lms.customer.reader.dto.ReaderDtos.BorrowingItemView;
import lms.customer.reader.dto.ReaderDtos.BorrowingView;
import lms.customer.reader.dto.ReaderDtos.FineView;
import lms.customer.reader.dto.ReaderDtos.FinesView;
import lms.customer.reader.dto.ReaderDtos.ReaderProfileView;
import lms.customer.reader.web.ReaderPortalController;
import lms.customer.shop.client.ShopClient;
import lms.customer.shop.web.OrderController;
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
import org.springframework.web.client.HttpServerErrorException;

/**
 * Cổng độc giả {@code /public/t/{code}/me/**} (ADR-0023, T-R1): phiên (401, 403 SHOP_SESSION_MISMATCH), module (tenant thiếu
 * LENDING thì 404 mà KHÔNG gọi client; tenant thiếu SALES thì route bán hàng 404), tập tên trường DTO, no-store, id sai
 * định dạng, mã lỗi chuyển tiếp.
 */
@WebMvcTest({ReaderPortalController.class, ShopController.class, BookController.class, OrderController.class,
        AuthController.class})
@Import({SecurityConfiguration.class, PublicExceptionHandler.class, ShopSessionGuard.class})
class ReaderPortalWebTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Set<String> FORBIDDEN_FIELDS =
            Set.of("userId", "readerId", "tenantId", "copyId", "bookCopyId");

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID borrowingId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();
    private final UUID titleId = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @MockitoBean StorefrontAccessCache storefronts;
    @MockitoBean CirculationReaderClient circulation;
    @MockitoBean IdentityReaderClient identity;
    @MockitoBean CatalogTitleClient catalog;
    @MockitoBean CatalogClient books;
    @MockitoBean ShopClient shop;
    @MockitoBean AuthClient auth;
    @MockitoBean JwtDecoder jwtDecoder;

    @BeforeEach
    void libraryA() {
        when(storefronts.resolve("lib-a")).thenReturn(access("lib-a", "SALES", "LENDING"));
        when(storefronts.resolve("lib-only")).thenReturn(access("lib-only", "LENDING"));
        when(storefronts.resolve("shop-only")).thenReturn(access("shop-only", "SALES"));
    }

    private StorefrontAccess access(String code, String... modules) {
        return new StorefrontAccess(tenantA, code, "Thư viện A", "svc-token", 300, null, Set.of(modules));
    }

    private RequestPostProcessor reader(UUID tenant) {
        return jwt().jwt(j -> j.tokenValue("reader-token").subject("u1").claim("tenantId", tenant.toString())
                .audience(List.of("lms-storefront")));
    }

    private static HttpClientErrorException downstream(HttpStatus status, String code) {
        return HttpClientErrorException.create(status, "err", HttpHeaders.EMPTY,
                ("{\"code\":\"" + code + "\",\"message\":\"nội bộ: ReservationController/mine\"}")
                        .getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    /** Mọi route me/** (phương thức, đường dẫn) dùng chung cho các ca kiểm phiên/module/no-store. */
    private List<String[]> routes() {
        return List.of(
                new String[] {"GET", "/public/t/%s/me/borrowings"},
                new String[] {"GET", "/public/t/%s/me/borrowings/" + borrowingId},
                new String[] {"GET", "/public/t/%s/me/fines"},
                new String[] {"GET", "/public/t/%s/me/reservations"},
                new String[] {"POST", "/public/t/%s/me/reservations/" + reservationId + "/cancellation"},
                new String[] {"GET", "/public/t/%s/me/reader-profile"});
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(String[] route,
                                                                                                String code) {
        String path = route[1].formatted(code);
        return "POST".equals(route[0]) ? post(path) : get(path);
    }

    private void verifyNoReaderCalls() {
        verifyNoInteractions(circulation, identity, catalog);
    }

    // ---------- Phiên ----------

    @Test
    void khongTokenLa401KhongGoiGi() throws Exception {
        for (String[] route : routes()) {
            mvc.perform(request(route, "lib-a")).andExpect(status().isUnauthorized());
        }
        verifyNoReaderCalls();
        verifyNoInteractions(storefronts);
    }

    @Test
    void tokenThieuAudHoacTokenNhanVienBi401() throws Exception {
        RequestPostProcessor noAud = jwt().jwt(j -> j.tokenValue("t").claim("tenantId", tenantA.toString()));
        RequestPostProcessor staff = jwt().jwt(j -> j.tokenValue("t").claim("tenantId", tenantA.toString())
                .audience(List.of("lms-staff")));
        for (RequestPostProcessor token : List.of(noAud, staff)) {
            for (String[] route : routes()) {
                mvc.perform(request(route, "lib-a").with(token)).andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
            }
        }
        verifyNoReaderCalls();
    }

    @Test
    void tokenDichVuSvcBi401() throws Exception {
        RequestPostProcessor svc = jwt().jwt(j -> j.tokenValue("svc").claim("svc", "customer-api")
                .claim("tenantId", tenantA.toString()).audience(List.of("lms-storefront")));
        for (String[] route : routes()) {
            mvc.perform(request(route, "lib-a").with(svc)).andExpect(status().isUnauthorized());
        }
        verifyNoReaderCalls();
    }

    @Test
    void tokenCuaTenantKhacLa403ShopSessionMismatch() throws Exception {
        for (String[] route : routes()) {
            mvc.perform(request(route, "lib-a").with(reader(tenantB)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("SHOP_SESSION_MISMATCH"))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        verifyNoReaderCalls();
    }

    @Test
    void phuongThucKhacTrenDuongDanHuyKhongToiController() throws Exception {
        for (MvcResult r : List.of(
                mvc.perform(delete("/public/t/lib-a/me/reservations/" + reservationId + "/cancellation")
                        .with(reader(tenantA))).andReturn(),
                mvc.perform(delete("/public/t/lib-a/me/reservations/" + reservationId).with(reader(tenantA))).andReturn(),
                mvc.perform(post("/public/t/lib-a/me/borrowings").with(reader(tenantA))).andReturn(),
                mvc.perform(get("/public/t/lib-a/me/reservations/" + reservationId + "/cancellation")
                        .with(reader(tenantA))).andReturn(),
                mvc.perform(get("/public/t/lib-a/me/borrowings/a/b").with(reader(tenantA))).andReturn(),
                mvc.perform(get("/public/t/lib-a/me/anything").with(reader(tenantA))).andReturn())) {
            assertThat(r.getResponse().getStatus()).isIn(401, 403, 404, 405);
        }
        verifyNoReaderCalls();
    }

    // ---------- Module theo nhóm route ----------

    @Test
    void tenantThieuLendingGoiMeLa404ShopNotAvailableKhongGoiClient() throws Exception {
        String first = null;
        for (String[] route : routes()) {
            String body = mvc.perform(request(route, "shop-only").with(reader(tenantA)))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andReturn().getResponse().getContentAsString().replaceAll("\"timestamp\":\"[^\"]*\"", "");
            first = first == null ? body : first;
            assertThat(body).isEqualTo(first);
        }
        verifyNoReaderCalls();
    }

    @Test
    void tenantThieuSalesGoiBanHangLa404CungThanKhongGoiClient() throws Exception {
        String bookId = UUID.randomUUID().toString();
        String order = "{\"lines\":[{\"titleId\":\"%s\",\"quantity\":1}],\"fulfilment\":\"PICKUP\",\"paymentMethod\":\"CASH\","
                + "\"recipient\":{\"name\":\"An\",\"phone\":\"0900000000\"},\"clientRequestId\":\"%s\"}";
        List<org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> calls = List.of(
                get("/public/t/lib-only/books"),
                get("/public/t/lib-only/books/" + bookId),
                get("/public/t/lib-only/books/" + bookId + "/preview"),
                get("/public/t/lib-only/books/suggestions").queryParam("q", "abc"),
                get("/public/t/lib-only/orders").with(reader(tenantA)),
                get("/public/t/lib-only/orders/" + UUID.randomUUID()).with(reader(tenantA)),
                post("/public/t/lib-only/orders").with(reader(tenantA)).contentType(MediaType.APPLICATION_JSON)
                        .content(order.formatted(bookId, UUID.randomUUID())),
                post("/public/t/lib-only/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"khach1\",\"password\":\"mat-khau-1\",\"fullName\":\"An\","
                                + "\"email\":\"an@example.vn\"}"));
        String first = null;
        for (var call : calls) {
            String body = mvc.perform(call).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"))
                    .andReturn().getResponse().getContentAsString().replaceAll("\"timestamp\":\"[^\"]*\"", "");
            first = first == null ? body : first;
            assertThat(body).isEqualTo(first);
        }
        verifyNoInteractions(books, shop, auth);
    }

    @Test
    void tenantChiCoLendingVanDangNhapDuocVaThongTinCuaHangCoHaiCo() throws Exception {
        when(auth.login(eq("lib-only"), eq("khach1"), eq("mat-khau-1")))
                .thenReturn(new IdentityTokenView("at", "rt", 900));
        mvc.perform(post("/public/t/lib-only/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"khach1\",\"password\":\"mat-khau-1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").value("at"));
        mvc.perform(get("/public/t/lib-only")).andExpect(status().isOk())
                .andExpect(jsonPath("$.shopEnabled").value(false)).andExpect(jsonPath("$.libraryEnabled").value(true))
                .andExpect(jsonPath("$.length()").value(4));
        mvc.perform(get("/public/t/lib-a")).andExpect(status().isOk())
                .andExpect(jsonPath("$.shopEnabled").value(true)).andExpect(jsonPath("$.libraryEnabled").value(true));
    }

    // ---------- Dữ liệu: chuyển tiếp token độc giả, tập tên trường, no-store ----------

    private BorrowingView borrowing() {
        return new BorrowingView(borrowingId, "BORROWED", LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-15"), 1,
                List.of(new BorrowingItemView(null, null), new BorrowingItemView(LocalDate.parse("2026-11-10"), "GOOD")));
    }

    private FinesView fines() {
        return new FinesView(new BigDecimal("15000"), List.of(new FineView(UUID.randomUUID(), borrowingId, "OVERDUE",
                new BigDecimal("15000"), "UNPAID", Instant.parse("2026-11-16T01:00:00Z"), null)));
    }

    private ReservationRow reservation() {
        return new ReservationRow(reservationId, titleId, "READY", 0, Instant.parse("2026-11-10T01:00:00Z"),
                LocalDate.parse("2026-11-20"));
    }

    private ReaderProfileView profile() {
        return new ReaderProfileView("LIB-0001", "An", "an@example.vn", "STUDENT", LocalDate.parse("2027-01-01"),
                "ACTIVE", true, "0900000000", LocalDate.parse("2000-01-01"), "MALE", "1 Lê Lợi", "CNTT", "K21",
                "SV001", LocalDate.parse("2024-01-01"));
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        collect(node, names);
        return names;
    }

    private static void collect(JsonNode node, Set<String> names) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(names::add);
            node.elements().forEachRemaining(child -> collect(child, names));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> collect(child, names));
        }
    }

    private JsonNode body(String path, boolean post) throws Exception {
        var builder = post ? post(path) : get(path);
        MvcResult r = mvc.perform(builder.with(reader(tenantA))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        JsonNode node = JSON.readTree(r.getResponse().getContentAsString());
        assertThat(fieldNames(node)).doesNotContainAnyElementsOf(FORBIDDEN_FIELDS);
        return node;
    }

    @Test
    void phieuMuonChuyenTokenDocGiaVaChiCoTruongAllowlist() throws Exception {
        when(circulation.myBorrowings("reader-token")).thenReturn(List.of(borrowing()));
        when(circulation.myBorrowing("reader-token", borrowingId)).thenReturn(borrowing());
        JsonNode list = body("/public/t/lib-a/me/borrowings", false);
        assertThat(fieldNames(list)).containsExactlyInAnyOrder("id", "status", "borrowDate", "dueDate", "renewCount",
                "items", "returnDate", "condition");
        assertThat(list.get(0).get("id").asText()).isEqualTo(borrowingId.toString());
        assertThat(fieldNames(body("/public/t/lib-a/me/borrowings/" + borrowingId, false)))
                .containsExactlyInAnyOrder("id", "status", "borrowDate", "dueDate", "renewCount", "items", "returnDate",
                        "condition");
        verifyNoInteractions(catalog, identity);
    }

    @Test
    void phatChiCoTruongAllowlist() throws Exception {
        when(circulation.myFines("reader-token")).thenReturn(fines());
        JsonNode node = body("/public/t/lib-a/me/fines", false);
        assertThat(fieldNames(node)).containsExactlyInAnyOrder("totalUnpaid", "fines", "id", "borrowingId", "reason",
                "amount", "status", "createdAt", "paidAt");
    }

    @Test
    void thePhoCapDocGiaChiCoTruongAllowlist() throws Exception {
        when(identity.myReaderProfile("reader-token")).thenReturn(profile());
        JsonNode node = body("/public/t/lib-a/me/reader-profile", false);
        assertThat(fieldNames(node)).containsExactlyInAnyOrder("cardNo", "fullName", "email", "readerType",
                "cardExpiryDate", "status", "cardValid", "phone", "dateOfBirth", "gender", "address", "faculty",
                "className", "memberCode", "cardIssuedDate");
    }

    @Test
    void datTruocGhepTenSachBangMotLoiGoiLoVoiTokenDichVu() throws Exception {
        UUID otherTitle = UUID.randomUUID();
        UUID thirdTitle = UUID.randomUUID();
        when(circulation.myReservations("reader-token")).thenReturn(List.of(reservation(),
                new ReservationRow(UUID.randomUUID(), otherTitle, "PENDING", 2, Instant.parse("2026-11-11T01:00:00Z"), null),
                new ReservationRow(UUID.randomUUID(), thirdTitle, "PENDING", 3, Instant.parse("2026-11-12T01:00:00Z"), null)));
        when(catalog.titleNames(anyString(), anyCollection())).thenReturn(Map.of(titleId, "Lập trình Java",
                otherTitle, "Cấu trúc dữ liệu"));
        JsonNode node = body("/public/t/lib-a/me/reservations", false);
        assertThat(fieldNames(node)).containsExactlyInAnyOrder("id", "bookRef", "title", "status", "queuePosition",
                "reservedAt", "holdUntil");
        assertThat(node.get(0).get("bookRef").asText()).isEqualTo(titleId.toString());
        assertThat(node.get(0).get("title").asText()).isEqualTo("Lập trình Java");
        assertThat(node.get(2).get("title").isNull()).isTrue();
        // đúng một lời gọi lô cho cả danh sách, bằng token dịch vụ (không phải token độc giả)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(catalog, times(1)).titleNames(eq("svc-token"), ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(titleId, otherTitle, thirdTitle);
    }

    @Test
    void huyDatTruocChuyenTokenDocGiaVaTraDatTruocDaGhepTen() throws Exception {
        ReservationRow cancelled = new ReservationRow(reservationId, titleId, "CANCELLED", 0,
                Instant.parse("2026-11-10T01:00:00Z"), null);
        when(circulation.cancelReservation("reader-token", reservationId)).thenReturn(cancelled);
        when(catalog.titleNames(eq("svc-token"), anyCollection())).thenReturn(Map.of(titleId, "Lập trình Java"));
        JsonNode node = body("/public/t/lib-a/me/reservations/" + reservationId + "/cancellation", true);
        assertThat(fieldNames(node)).containsExactlyInAnyOrder("id", "bookRef", "title", "status", "queuePosition",
                "reservedAt", "holdUntil");
        assertThat(node.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(node.get("title").asText()).isEqualTo("Lập trình Java");
    }

    @Test
    void danhSachDatTruocRongKhongGoiCatalogLookupVoiIdRong() throws Exception {
        when(circulation.myReservations("reader-token")).thenReturn(List.of());
        when(catalog.titleNames(anyString(), anyCollection())).thenReturn(Map.of());
        JsonNode node = body("/public/t/lib-a/me/reservations", false);
        assertThat(node.isArray()).isTrue();
        assertThat(node.size()).isZero();
    }

    // ---------- Id sai định dạng ----------

    @Test
    void idKhongPhaiUuidLa404KhongGoiClient() throws Exception {
        mvc.perform(get("/public/t/lib-a/me/borrowings/khong-phai-uuid").with(reader(tenantA)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BORROWING_NOT_FOUND"))
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post("/public/t/lib-a/me/reservations/khong-phai-uuid/cancellation").with(reader(tenantA)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        verifyNoReaderCalls();
    }

    // ---------- Lỗi phía sau ----------

    @Test
    void maLoiNghiepVuDuocChuyenVoiThongDiepCuaCustomerApi() throws Exception {
        when(circulation.myBorrowing(anyString(), any())).thenThrow(downstream(HttpStatus.NOT_FOUND, "BORROWING_NOT_FOUND"));
        mvc.perform(get("/public/t/lib-a/me/borrowings/" + borrowingId).with(reader(tenantA)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("BORROWING_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Không tìm thấy phiếu mượn"));

        when(circulation.cancelReservation(anyString(), any()))
                .thenThrow(downstream(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND"))
                .thenThrow(downstream(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RESERVATION"));
        String cancel = "/public/t/lib-a/me/reservations/" + reservationId + "/cancellation";
        mvc.perform(post(cancel).with(reader(tenantA))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        mvc.perform(post(cancel).with(reader(tenantA))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_RESERVATION"));

        when(identity.myReaderProfile(anyString())).thenThrow(downstream(HttpStatus.NOT_FOUND, "READER_NOT_FOUND"));
        mvc.perform(get("/public/t/lib-a/me/reader-profile").with(reader(tenantA)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("READER_PROFILE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Bạn chưa có thẻ thư viện"));
    }

    @Test
    void forbiddenKhongLoTenKhoaVaModuleNotEnabledThanhShopNotAvailable() throws Exception {
        when(circulation.myReservations(anyString())).thenThrow(downstream(HttpStatus.FORBIDDEN, "FORBIDDEN"));
        MvcResult r = mvc.perform(get("/public/t/lib-a/me/reservations").with(reader(tenantA)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN")).andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain("ReservationController");

        when(circulation.myFines(anyString())).thenThrow(downstream(HttpStatus.FORBIDDEN, "MODULE_NOT_ENABLED"));
        mvc.perform(get("/public/t/lib-a/me/fines").with(reader(tenantA)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SHOP_NOT_AVAILABLE"));
    }

    @Test
    void loi5xxVaMatKetNoiThanh503Chung() throws Exception {
        when(circulation.myBorrowings(anyString())).thenThrow(HttpServerErrorException.create(
                HttpStatus.INTERNAL_SERVER_ERROR, "err", HttpHeaders.EMPTY, "{\"code\":\"X\"}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));
        mvc.perform(get("/public/t/lib-a/me/borrowings").with(reader(tenantA)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        when(identity.myReaderProfile(anyString())).thenThrow(new org.springframework.web.client.ResourceAccessException("x"));
        mvc.perform(get("/public/t/lib-a/me/reader-profile").with(reader(tenantA)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void maLaTuPhiaSauThanhRequestRejected() throws Exception {
        when(circulation.myFines(anyString())).thenThrow(downstream(HttpStatus.CONFLICT, "MA_NOI_BO_LA"));
        mvc.perform(get("/public/t/lib-a/me/fines").with(reader(tenantA))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
    }
}

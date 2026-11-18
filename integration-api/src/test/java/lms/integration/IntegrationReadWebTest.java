package lms.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.integration.catalog.client.CatalogClient;
import lms.integration.catalog.dto.BookDtos.BookView;
import lms.integration.catalog.web.BookController;
import lms.integration.common.dto.PageView;
import lms.integration.config.SecurityConfiguration;
import lms.integration.config.TestTokens;
import lms.integration.inventory.client.InventoryClient;
import lms.integration.inventory.dto.InventoryDtos.InventoryItemView;
import lms.integration.inventory.web.InventoryController;
import lms.integration.orders.client.OrderClient;
import lms.integration.orders.dto.OrderDtos.OrderLineView;
import lms.integration.orders.dto.OrderDtos.OrderView;
import lms.integration.orders.web.OrderController;
import lms.integration.ratelimit.ApiCaller;
import lms.integration.ratelimit.ApiRateLimiter;
import lms.integration.web.IntegrationApiException;
import lms.integration.web.IntegrationExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Endpoint đọc v1 (pha I3): scope kiểm sớm (403 INSUFFICIENT_SCOPE), token sai aud/iss/hết hạn là 401, giới hạn tốc độ
 * theo application (429 + Retry-After), tenant lấy từ token (không từ tham số/header), lỗi hạ nguồn chuẩn hóa, log truy
 * cập không lộ token. Client hạ nguồn là giả; việc chuyển token và đường dẫn thật ở Http*ClientTest.
 */
@ExtendWith(OutputCaptureExtension.class)
@WebMvcTest({BookController.class, InventoryController.class, OrderController.class})
@Import({SecurityConfiguration.class, IntegrationExceptionHandler.class, IntegrationReadWebTest.Beans.class})
class IntegrationReadWebTest {

    static final TestTokens TOKENS = new TestTokens();
    static final UUID TENANT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final UUID APPLICATION = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    static final String ALL_SCOPES = "books:read inventory:read orders:read";

    /** Bộ giới hạn giả: ghi lại người gọi + nhóm, trả quyết định đặt sẵn. */
    static class RecordingLimiter implements ApiRateLimiter {
        final List<ApiCaller> callers = new ArrayList<>();
        final List<String> groups = new ArrayList<>();
        Optional<Decision> next = Optional.of(new Decision(true, 600, 599, 60));

        @Override
        public Optional<Decision> check(ApiCaller caller, String group) {
            callers.add(caller);
            groups.add(group);
            return next;
        }
    }

    @TestConfiguration
    static class Beans {
        @Bean
        JwtDecoder jwtDecoder() {
            return TOKENS.decoder();
        }

        @Bean
        RecordingLimiter recordingLimiter() {
            return new RecordingLimiter();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired RecordingLimiter limiter;
    @MockitoBean CatalogClient catalog;
    @MockitoBean InventoryClient inventory;
    @MockitoBean OrderClient orders;

    @BeforeEach
    void reset() {
        limiter.callers.clear();
        limiter.groups.clear();
        limiter.next = Optional.of(new ApiRateLimiter.Decision(true, 600, 599, 60));
    }

    private static String bearer(String scope) {
        return "Bearer " + token(scope, TENANT, APPLICATION);
    }

    static String token(String scope, UUID tenant, UUID application) {
        Map<String, Object> claims = TestTokens.claims();
        claims.put("scope", scope);
        claims.put("tenantId", tenant.toString());
        claims.put("application_id", application.toString());
        return TOKENS.with(TestTokens.ISSUER, List.of("lms-integration"), Duration.ofMinutes(5), claims);
    }

    private static BookView book(UUID id) {
        return new BookView(id, "Clean Code", null, "Robert Martin", List.of(), "9780132350884",
                new BigDecimal("250000"), null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, List.of(), null, null, null, null);
    }

    private static InventoryItemView item(UUID titleId) {
        return new InventoryItemView(titleId, "Clean Code", "SKU-1", 10, 2, 8, 200000L, 200000L, false, true, 3, null);
    }

    private static OrderView order(UUID id) {
        return new OrderView(id, "SO-1", "ONLINE", "CONFIRMED", "PAID", "COD", "SHIPPING", "VND", 800, true, 100000,
                0, 8000, 0, 100000, Instant.parse("2026-01-01T00:00:00Z"), null, null, null, null, 3,
                List.of(new OrderLineView(1, UUID.randomUUID(), "Clean Code", "9780132350884", 1, 100000, 0, 0,
                        100000, 8000, 100000)));
    }

    // --- 200 với đủ scope ---

    @Test
    void sachTheoIdDuScopeTra200VaNoStore() throws Exception {
        UUID id = UUID.randomUUID();
        when(catalog.get(id)).thenReturn(book(id));
        mvc.perform(get("/api/integration/v1/books/" + id).header("Authorization", bearer("books:read")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(id.toString())).andExpect(jsonPath("$.name").value("Clean Code"))
                .andExpect(jsonPath("$.tenantId").doesNotExist());
    }

    @Test
    void lookupVaLookupTheoIsbnDuScopeTra200() throws Exception {
        UUID id = UUID.randomUUID();
        when(catalog.lookup(List.of(id))).thenReturn(List.of(book(id)));
        when(catalog.lookupByIsbn(List.of("9780132350884"))).thenReturn(List.of(book(id)));
        mvc.perform(post("/api/integration/v1/books/lookup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + id + "\"]}").header("Authorization", bearer("books:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id.toString()));
        mvc.perform(post("/api/integration/v1/books/lookup-by-isbn").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isbns\":[\"9780132350884\"]}").header("Authorization", bearer("books:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].isbn").value("9780132350884"));
    }

    @Test
    void lookupQua100IdHoacRongHoacSaiKieuLa400() throws Exception {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 101; i++) many.add("\"" + UUID.randomUUID() + "\"");
        for (String body : List.of("{\"ids\":[" + String.join(",", many) + "]}", "{\"ids\":[]}", "{}",
                "{\"ids\":[\"khong-phai-uuid\"]}", "khong-phai-json")) {
            mvc.perform(post("/api/integration/v1/books/lookup").contentType(MediaType.APPLICATION_JSON)
                            .content(body).header("Authorization", bearer("books:read")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        mvc.perform(post("/api/integration/v1/books/lookup-by-isbn").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isbns\":[\"\"]}").header("Authorization", bearer("books:read")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(catalog);
    }

    @Test
    void idKhongPhaiUuidLa400() throws Exception {
        mvc.perform(get("/api/integration/v1/books/abc").header("Authorization", bearer("books:read")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void tonKhoDanhSachVaChiTiet() throws Exception {
        UUID titleId = UUID.randomUUID();
        when(inventory.list(true, 1, 50)).thenReturn(new PageView<>(List.of(item(titleId)), 1, 1, 50));
        when(inventory.get(titleId)).thenReturn(item(titleId));
        mvc.perform(get("/api/integration/v1/inventory?lowStockOnly=true&page=1&size=50")
                        .header("Authorization", bearer("inventory:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].titleId").value(titleId.toString()))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].costPrice").doesNotExist());
        mvc.perform(get("/api/integration/v1/inventory/" + titleId).header("Authorization", bearer("inventory:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(8));
        verify(inventory, never()).list(false, 0, 20);
    }

    @Test
    void tonKhoMacDinhVaPhanTrangSaiLa400() throws Exception {
        when(inventory.list(false, 0, 20)).thenReturn(new PageView<>(List.of(), 0, 0, 20));
        mvc.perform(get("/api/integration/v1/inventory").header("Authorization", bearer("inventory:read")))
                .andExpect(status().isOk());
        for (String q : List.of("size=101", "size=0", "page=-1", "page=abc")) {
            mvc.perform(get("/api/integration/v1/inventory?" + q).header("Authorization", bearer("inventory:read")))
                    .andExpect(status().isBadRequest());
        }
        verify(inventory).list(false, 0, 20);
    }

    @Test
    void donHangDanhSachVaChiTiet() throws Exception {
        UUID id = UUID.randomUUID();
        when(orders.list("CONFIRMED", 0, 20)).thenReturn(new PageView<>(List.of(order(id)), 1, 0, 20));
        when(orders.get(id)).thenReturn(order(id));
        mvc.perform(get("/api/integration/v1/orders?status=confirmed").header("Authorization", bearer("orders:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].orderNo").value("SO-1"))
                .andExpect(jsonPath("$.items[0].lines[0].lineNo").value(1));
        mvc.perform(get("/api/integration/v1/orders/" + id).header("Authorization", bearer("orders:read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.contact").doesNotExist()).andExpect(jsonPath("$.customerNote").doesNotExist());
        mvc.perform(get("/api/integration/v1/orders?status=KHONG_CO").header("Authorization", bearer("orders:read")))
                .andExpect(status().isBadRequest());
    }

    // --- scope ---

    @Test
    void thieuHoacSaiScopeBi403VoiMoiEndpointVaKhongGoiHaNguon() throws Exception {
        UUID id = UUID.randomUUID();
        Map<String, List<String>> urls = Map.of(
                "books:read", List.of("/api/integration/v1/inventory", "/api/integration/v1/orders"),
                "inventory:read", List.of("/api/integration/v1/books/" + id, "/api/integration/v1/orders/" + id),
                "orders:read", List.of("/api/integration/v1/books/" + id, "/api/integration/v1/inventory/" + id));
        for (var e : urls.entrySet()) {
            for (String url : e.getValue()) {
                mvc.perform(get(url).header("Authorization", bearer(e.getKey())))
                        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));
            }
        }
        // POST lookup cũng cần books:read
        mvc.perform(post("/api/integration/v1/books/lookup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + id + "\"]}").header("Authorization", bearer("orders:read")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));
        // scope ghi (chưa có ở I3) và token không scope không thay thế được scope đọc
        mvc.perform(get("/api/integration/v1/books/" + id).header("Authorization", bearer("books:write")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/integration/v1/books/" + id).header("Authorization", bearer("")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(catalog, inventory, orders);
    }

    @Test
    void scopeSoKhopNguyenTuKhongSoChuoiCon() throws Exception {
        mvc.perform(get("/api/integration/v1/books/" + UUID.randomUUID())
                        .header("Authorization", bearer("xbooks:read books:readx")))
                .andExpect(status().isForbidden());
    }

    @Test
    void duongDanKhongMoChoTichHopLa404KeCaLienHeVaGhi() throws Exception {
        UUID id = UUID.randomUUID();
        String auth = bearer(ALL_SCOPES);
        mvc.perform(get("/api/integration/v1/orders/" + id + "/contact").header("Authorization", auth))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/integration/v1/orders/" + id + "/notes").header("Authorization", auth))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/integration/v1/customers").header("Authorization", auth))
                .andExpect(status().isNotFound());
        for (var req : List.of(post("/api/integration/v1/orders"), put("/api/integration/v1/books/" + id),
                delete("/api/integration/v1/inventory/" + id), post("/api/integration/v1/books"))) {
            int code = mvc.perform(req.contentType(MediaType.APPLICATION_JSON).content("{}")
                    .header("Authorization", auth)).andReturn().getResponse().getStatus();
            assertThat(code).isIn(403, 404, 405);
        }
        verifyNoInteractions(catalog, inventory, orders);
    }

    // --- 401 ---

    @Test
    void khongTokenVaTokenSaiBi401() throws Exception {
        UUID id = UUID.randomUUID();
        String url = "/api/integration/v1/books/" + id;
        mvc.perform(get(url)).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        Map<String, Object> claims = TestTokens.claims();
        claims.put("scope", ALL_SCOPES);
        String wrongAud = TOKENS.with(TestTokens.ISSUER, List.of("lms-staff"), Duration.ofMinutes(5), claims);
        String noAud = TOKENS.with(TestTokens.ISSUER, null, Duration.ofMinutes(5), claims);
        String storefront = TOKENS.with(TestTokens.ISSUER, List.of("lms-storefront"), Duration.ofMinutes(5), claims);
        String wrongIss = TOKENS.with("ke-gia-mao", List.of("lms-integration"), Duration.ofMinutes(5), claims);
        String expired = TOKENS.with(TestTokens.ISSUER, List.of("lms-integration"), Duration.ofSeconds(-120), claims);
        for (String t : List.of(wrongAud, noAud, storefront, wrongIss, expired, "khong.phai.jwt")) {
            mvc.perform(get(url).header("Authorization", "Bearer " + t)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        verifyNoInteractions(catalog);
        assertThat(limiter.callers).as("request 401 không được đếm vào hạn mức").isEmpty();
    }

    // --- tenant lấy từ token ---

    @Test
    void tenantLayTuTokenKhongTuThamSoHayHeader() throws Exception {
        UUID otherTenant = UUID.fromString("cccccccc-0000-0000-0000-000000000009");
        when(inventory.list(anyBoolean(), anyInt(), anyInt())).thenReturn(new PageView<>(List.of(), 0, 0, 20));
        mvc.perform(get("/api/integration/v1/inventory?tenantId=" + otherTenant)
                        .header("X-Tenant-Id", otherTenant.toString()).header("Authorization", bearer("inventory:read")))
                .andExpect(status().isOk());
        assertThat(limiter.callers).hasSize(1);
        assertThat(limiter.callers.get(0).tenantId()).isEqualTo(TENANT).isNotEqualTo(otherTenant);
        assertThat(limiter.callers.get(0).applicationId()).isEqualTo(APPLICATION);
        // Client hạ nguồn không nhận tham số tenant nào (chữ ký chỉ có lowStockOnly, page, size)
        verify(inventory).list(false, 0, 20);
    }

    // --- rate limit ---

    @Test
    void vuotHanMucTra429KemRetryAfterVaKhongGoiHaNguon() throws Exception {
        limiter.next = Optional.of(new ApiRateLimiter.Decision(false, 5, 0, 17));
        mvc.perform(get("/api/integration/v1/books/" + UUID.randomUUID()).header("Authorization", bearer("books:read")))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "17"))
                .andExpect(header().string("X-RateLimit-Limit", "5"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        verifyNoInteractions(catalog);
    }

    @Test
    void duoiHanMucCoHeaderConLaiVaNhomEndpointDung() throws Exception {
        limiter.next = Optional.of(new ApiRateLimiter.Decision(true, 600, 42, 30));
        when(inventory.list(false, 0, 20)).thenReturn(new PageView<>(List.of(), 0, 0, 20));
        when(orders.list(null, 0, 20)).thenReturn(new PageView<>(List.of(), 0, 0, 20));
        when(catalog.lookup(any())).thenReturn(List.of());
        mvc.perform(get("/api/integration/v1/inventory").header("Authorization", bearer("inventory:read")))
                .andExpect(status().isOk()).andExpect(header().string("X-RateLimit-Limit", "600"))
                .andExpect(header().string("X-RateLimit-Remaining", "42"));
        mvc.perform(get("/api/integration/v1/orders").header("Authorization", bearer("orders:read")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/integration/v1/books/lookup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[\"" + UUID.randomUUID() + "\"]}").header("Authorization", bearer("books:read")))
                .andExpect(status().isOk());
        assertThat(limiter.groups).containsExactly("inventory", "orders", "books");
    }

    @Test
    void boDemRong_FailOpen_khongChanRequest() throws Exception {
        limiter.next = Optional.empty();
        when(inventory.list(false, 0, 20)).thenReturn(new PageView<>(List.of(), 0, 0, 20));
        mvc.perform(get("/api/integration/v1/inventory").header("Authorization", bearer("inventory:read")))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("X-RateLimit-Limit"));
    }

    // --- lỗi hạ nguồn ---

    @Test
    void loiHaNguonDuocChuanHoa() throws Exception {
        UUID id = UUID.randomUUID();
        when(catalog.get(id)).thenThrow(IntegrationApiException.serviceUnavailable());
        mvc.perform(get("/api/integration/v1/books/" + id).header("Authorization", bearer("books:read")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        when(orders.get(id)).thenThrow(new IntegrationApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Token không có quyền truy cập tài nguyên này"));
        mvc.perform(get("/api/integration/v1/orders/" + id).header("Authorization", bearer("orders:read")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        when(inventory.get(id)).thenThrow(new IntegrationApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "x"));
        mvc.perform(get("/api/integration/v1/inventory/" + id).header("Authorization", bearer("inventory:read")))
                .andExpect(status().isNotFound());
    }

    @Test
    void loiBatNgoKhongLoThongTin() throws Exception {
        UUID id = UUID.randomUUID();
        when(catalog.get(eq(id))).thenThrow(new IllegalStateException("jdbc:postgresql://noi-bo secret"));
        String body = mvc.perform(get("/api/integration/v1/books/" + id).header("Authorization", bearer("books:read")))
                .andExpect(status().isInternalServerError()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("jdbc").doesNotContain("secret");
    }

    // --- log truy cập ---

    @Test
    void logTruyCapCoCauTrucVaKhongLoTokenHayUrlDayDu(CapturedOutput output) throws Exception {
        UUID id = UUID.randomUUID();
        when(catalog.get(id)).thenReturn(book(id));
        String token = token("books:read", TENANT, APPLICATION);
        mvc.perform(get("/api/integration/v1/books/" + id + "?q=bi-mat-trong-query")
                        .header("Authorization", "Bearer " + token).header("X-Correlation-Id", "corr-123"))
                .andExpect(status().isOk());
        String line = output.getAll().lines().filter(l -> l.contains("integration-access")).findFirst().orElseThrow();
        assertThat(line).contains("tenantId=" + TENANT).contains("applicationId=" + APPLICATION)
                .contains("method=GET").contains("route=/api/integration/v1/books/{id}").contains("status=200")
                .contains("durationMs=").contains("jti=");
        String all = output.getAll();
        assertThat(all).doesNotContain(token).doesNotContain("Bearer ").doesNotContain("Authorization")
                .doesNotContain("bi-mat-trong-query").doesNotContain(id.toString());
    }

    @Test
    void logCaRequestBi401VaBi403(CapturedOutput output) throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/api/integration/v1/books/" + id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/integration/v1/books/" + id).header("Authorization", bearer("orders:read")))
                .andExpect(status().isForbidden());
        List<String> lines = output.getAll().lines().filter(l -> l.contains("integration-access")).toList();
        assertThat(lines).anyMatch(l -> l.contains("status=401") && l.contains("tenantId=- "));
        assertThat(lines).anyMatch(l -> l.contains("status=403") && l.contains("tenantId=" + TENANT));
    }
}

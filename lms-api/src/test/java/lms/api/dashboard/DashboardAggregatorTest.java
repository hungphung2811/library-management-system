package lms.api.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lms.api.dashboard.client.CatalogTitleClient;
import lms.api.dashboard.client.DashboardStatsClient;
import lms.api.dashboard.client.PlatformModulesClient;
import lms.api.dashboard.dto.SummaryView;
import lms.api.dashboard.service.DashboardAggregator;
import lms.api.dashboard.service.DashboardException;
import lms.api.dashboard.service.DashboardExecutor;
import lms.api.dashboard.service.ModuleResolver;
import lms.api.dashboard.service.TitleNameEnricher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/** Quy tắc gộp summary (ADR-0018 mục 2.1): suy giảm một phần, module tắt, lỗi mức người gọi, hạn chót chung. */
class DashboardAggregatorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TENANT = "22222222-2222-2222-2222-222222222222";
    private static final UUID TITLE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    DashboardStatsClient stats = mock(DashboardStatsClient.class);
    CatalogTitleClient titleClient = mock(CatalogTitleClient.class);
    PlatformModulesClient platformModules = mock(PlatformModulesClient.class);
    DashboardExecutor executor = new DashboardExecutor(4, 4, 8);

    @BeforeEach
    void happyPath() throws Exception {
        when(stats.catalogSummary()).thenReturn(json("{\"titles\":60,\"copies\":{\"total\":150}}"));
        when(stats.lendingSummary(any())).thenReturn(
                json("{\"lending\":{\"overdueBorrowings\":6},\"fines\":{\"unpaidCount\":4}}"));
        when(stats.salesSummary(any())).thenReturn(json("{\"ordersToday\":2,\"lowStockItems\":[]}"));
    }

    @AfterEach
    void stop() {
        executor.destroy();
    }

    private DashboardAggregator aggregator(Duration deadline) {
        return new DashboardAggregator(stats, new ModuleResolver(platformModules), new TitleNameEnricher(titleClient),
                executor, Clock.fixed(Instant.parse("2026-11-17T17:10:00Z"), ZoneOffset.UTC), deadline);
    }

    private DashboardAggregator aggregator() {
        return aggregator(Duration.ofSeconds(3));
    }

    private static JsonNode json(String raw) throws Exception {
        return JSON.readTree(raw);
    }

    private static Jwt tenantToken(String... modules) {
        return Jwt.withTokenValue("t").header("alg", "none").subject("u").claim("tenantId", TENANT)
                .claim("modules", List.of(modules)).build();
    }

    private static Jwt platformToken() {
        return Jwt.withTokenValue("p").header("alg", "none").subject("pa").claim("platformAdmin", true).build();
    }

    private static HttpClientErrorException client(HttpStatus status, String code) {
        return HttpClientErrorException.create(status, "err", HttpHeaders.EMPTY,
                ("{\"code\":\"" + code + "\",\"message\":\"x\"}").getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private static HttpServerErrorException server500() {
        return HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "err", HttpHeaders.EMPTY,
                "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    @Test
    void tatCaNguonOkTachLendingVaFinesVaDungMocHomNayGioVn() {
        SummaryView view = aggregator().summary(tenantToken("LENDING", "SALES"), null);

        assertEquals("Asia/Ho_Chi_Minh", view.timezone());
        // 17:10 UTC ngày 17 = 00:10 giờ VN ngày 18
        assertEquals(LocalDate.of(2026, 11, 18), view.generatedAt().toLocalDate());
        assertEquals("+07:00", view.generatedAt().getOffset().toString());
        verify(stats).lendingSummary(LocalDate.of(2026, 11, 18));
        verify(stats).salesSummary(LocalDate.of(2026, 11, 18));
        assertEquals(60, view.catalog().get("titles").asInt());
        assertEquals(6, view.lending().get("overdueBorrowings").asInt());
        assertEquals(4, view.fines().get("unpaidCount").asInt());
        assertEquals(2, view.sales().get("ordersToday").asInt());
        assertEquals(List.of("LENDING", "SALES"), view.modules());
        assertTrue(view.disabledModules().isEmpty() && view.forbiddenSections().isEmpty()
                && view.unavailableSections().isEmpty());
        assertNull(view.tenantId());
    }

    @Test
    void tenantChiBatLendingKhongGoiSalesVaGhiDisabledModules() {
        SummaryView view = aggregator().summary(tenantToken("LENDING"), null);

        verify(stats, never()).salesSummary(any());
        assertNull(view.sales());
        assertEquals(List.of("SALES"), view.disabledModules());
        assertEquals(List.of("LENDING"), view.modules());
        assertNotNull(view.lending());
        assertNotNull(view.fines());
    }

    @Test
    void tenantChiBatSalesAnLendingFinesVaSoBanSao() {
        SummaryView view = aggregator().summary(tenantToken("SALES"), null);

        verify(stats, never()).lendingSummary(any());
        assertNull(view.lending());
        assertNull(view.fines());
        assertTrue(view.catalog().get("copies").isNull(), "số bản sao thuộc LENDING");
        assertEquals(60, view.catalog().get("titles").asInt());
        assertEquals(List.of("LENDING"), view.disabledModules());
        assertNotNull(view.sales());
    }

    @Test
    void motNguon500VanTra200ChiPhanDoNull() {
        when(stats.salesSummary(any())).thenThrow(server500());

        SummaryView view = aggregator().summary(tenantToken("LENDING", "SALES"), null);

        assertNull(view.sales());
        assertEquals(List.of("sales"), view.unavailableSections());
        assertNotNull(view.catalog());
        assertNotNull(view.lending());
    }

    @Test
    void matKetNoiLaUnavailableVaLendingKemTheoFines() {
        when(stats.lendingSummary(any())).thenThrow(new ResourceAccessException("refused"));

        SummaryView view = aggregator().summary(tenantToken("LENDING", "SALES"), null);

        assertNull(view.lending());
        assertNull(view.fines());
        assertEquals(List.of("lending", "fines"), view.unavailableSections());
        assertNotNull(view.sales());
    }

    @Test
    void quaHanChotChungThiNguonDoUnavailableVaKhongChoLauHon() {
        when(stats.lendingSummary(any())).thenAnswer(i -> {
            Thread.sleep(1500);
            return json("{}");
        });
        long started = System.nanoTime();

        SummaryView view = aggregator(Duration.ofMillis(300)).summary(tenantToken("LENDING", "SALES"), null);

        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertTrue(elapsedMs < 1200, "không chờ nguồn chậm quá hạn chót, đã chờ " + elapsedMs + "ms");
        assertEquals(List.of("lending", "fines"), view.unavailableSections());
        assertNotNull(view.catalog());
        assertNotNull(view.sales());
    }

    @Test
    void nguon403XepForbiddenSectionsCoFines() {
        when(stats.lendingSummary(any())).thenThrow(client(HttpStatus.FORBIDDEN, "FORBIDDEN"));

        SummaryView view = aggregator().summary(tenantToken("LENDING", "SALES"), null);

        assertEquals(List.of("lending", "fines"), view.forbiddenSections());
        assertNull(view.lending());
        assertNotNull(view.sales());
    }

    @Test
    void sales403VaCatalogOkChiCoSalesBiCam() {
        when(stats.salesSummary(any())).thenThrow(client(HttpStatus.FORBIDDEN, "FORBIDDEN"));

        SummaryView view = aggregator().summary(tenantToken("LENDING", "SALES"), null);

        assertEquals(List.of("sales"), view.forbiddenSections());
        assertNotNull(view.lending());
    }

    @Test
    void module403CuaServiceKhiClaimCuXepDisabledModules() {
        when(stats.salesSummary(any())).thenThrow(client(HttpStatus.FORBIDDEN, "MODULE_NOT_ENABLED"));

        SummaryView view = aggregator().summary(tenantToken("LENDING", "SALES"), null);

        assertEquals(List.of("SALES"), view.disabledModules());
        assertTrue(view.forbiddenSections().isEmpty());
        assertEquals(List.of("LENDING"), view.modules());
    }

    @Test
    void moiNguonDaGoiDeu403ThiTraForbidden() {
        when(stats.catalogSummary()).thenThrow(client(HttpStatus.FORBIDDEN, "FORBIDDEN"));
        when(stats.lendingSummary(any())).thenThrow(client(HttpStatus.FORBIDDEN, "FORBIDDEN"));
        when(stats.salesSummary(any())).thenThrow(client(HttpStatus.FORBIDDEN, "FORBIDDEN"));

        DashboardException ex = assertThrows(DashboardException.class,
                () -> aggregator().summary(tenantToken("LENDING", "SALES"), null));

        assertEquals(HttpStatus.FORBIDDEN, ex.status());
        assertEquals("FORBIDDEN", ex.code());
    }

    @Test
    void moiNguonDaGoiDeuHongThiTra503() {
        when(stats.catalogSummary()).thenThrow(server500());
        when(stats.lendingSummary(any())).thenThrow(new ResourceAccessException("down"));
        when(stats.salesSummary(any())).thenThrow(server500());

        DashboardException ex = assertThrows(DashboardException.class,
                () -> aggregator().summary(tenantToken("LENDING", "SALES"), null));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.status());
        assertEquals("SERVICE_UNAVAILABLE", ex.code());
    }

    @Test
    void khongDuDieuKienThiVan200LoiHonHop() {
        // catalog ok, lending 403, sales 500: không phải "tất cả cùng loại" nên 200
        when(stats.lendingSummary(any())).thenThrow(client(HttpStatus.FORBIDDEN, "FORBIDDEN"));
        when(stats.salesSummary(any())).thenThrow(server500());

        SummaryView view = aggregator().summary(tenantToken("LENDING", "SALES"), null);

        assertNotNull(view.catalog());
        assertEquals(List.of("lending", "fines"), view.forbiddenSections());
        assertEquals(List.of("sales"), view.unavailableSections());
    }

    @Test
    void loiMucNguoiGoi401VaTenantRequiredVaInvalidTenantIdDuocNemLaiNguyen() {
        when(stats.catalogSummary()).thenThrow(client(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"));
        RestClientResponseException ex401 = assertThrows(RestClientResponseException.class,
                () -> aggregator().summary(tenantToken("LENDING", "SALES"), null));
        assertEquals(401, ex401.getStatusCode().value());

        doThrow(client(HttpStatus.FORBIDDEN, "TENANT_REQUIRED")).when(stats).catalogSummary();
        RestClientResponseException ex403 = assertThrows(RestClientResponseException.class,
                () -> aggregator().summary(tenantToken("LENDING", "SALES"), null));
        assertEquals(403, ex403.getStatusCode().value());
        assertTrue(ex403.getResponseBodyAsString().contains("TENANT_REQUIRED"));

        doThrow(client(HttpStatus.BAD_REQUEST, "INVALID_TENANT_ID")).when(stats).catalogSummary();
        RestClientResponseException ex400 = assertThrows(RestClientResponseException.class,
                () -> aggregator().summary(tenantToken("LENDING", "SALES"), null));
        assertEquals(400, ex400.getStatusCode().value());
    }

    @Test
    void platformAdminCoXTenantIdTraModuleCuaTenantDichVaGhiTenantId() {
        when(platformModules.enabledModules(UUID.fromString(TENANT))).thenReturn(Set.of("LENDING"));

        SummaryView view = aggregator().summary(platformToken(), TENANT);

        assertEquals(TENANT, view.tenantId());
        assertEquals(List.of("SALES"), view.disabledModules());
        assertNull(view.sales());
        verify(stats, never()).salesSummary(any());
        assertNotNull(view.lending());
    }

    @Test
    void platformAdminTraModuleLoi5xxThiLendingVaSalesUnavailableCatalogVanCo() {
        when(platformModules.enabledModules(any())).thenThrow(server500());

        SummaryView view = aggregator().summary(platformToken(), TENANT);

        assertNotNull(view.catalog());
        assertNull(view.lending());
        assertNull(view.sales());
        assertEquals(List.of("lending", "sales", "fines"), view.unavailableSections());
        verify(stats, never()).lendingSummary(any());
    }

    @Test
    void platformAdminTenantKhongTonTai404DuocNemLai() {
        when(platformModules.enabledModules(any())).thenThrow(client(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND"));

        RestClientResponseException ex = assertThrows(RestClientResponseException.class,
                () -> aggregator().summary(platformToken(), TENANT));

        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void platformAdminThieuHoacSaiHeaderKhongTraModuleDeServiceTraLoi() {
        when(stats.catalogSummary()).thenThrow(client(HttpStatus.FORBIDDEN, "TENANT_REQUIRED"));
        assertThrows(RestClientResponseException.class, () -> aggregator().summary(platformToken(), null));
        assertThrows(RestClientResponseException.class, () -> aggregator().summary(platformToken(), "khong-phai-uuid"));
        verify(platformModules, never()).enabledModules(any());
    }

    @Test
    void tenantAdminGuiHeaderTenantKhacBiBoQuaKhongTraModule() {
        SummaryView view = aggregator().summary(tenantToken("LENDING", "SALES"), "99999999-9999-9999-9999-999999999999");

        verify(platformModules, never()).enabledModules(any());
        assertNull(view.tenantId());
    }

    @Test
    void tokenKhongCoClaimModulesThiGoiHetDeServiceQuyetDinh() {
        Jwt legacy = Jwt.withTokenValue("t").header("alg", "none").subject("u").claim("tenantId", TENANT).build();

        SummaryView view = aggregator().summary(legacy, null);

        verify(stats).lendingSummary(any());
        verify(stats).salesSummary(any());
        assertNotNull(view.sales());
    }

    @Test
    void boSungTenMatHangSapHetTuCatalogVaLoiCatalogThiTitleNameNull() throws Exception {
        when(stats.salesSummary(any())).thenReturn(json("{\"ordersToday\":1,\"lowStockItems\":["
                + "{\"titleId\":\"" + TITLE + "\",\"available\":0,\"threshold\":5}]}"));
        when(titleClient.names(any())).thenReturn(Map.of(TITLE, "Clean Code"));
        SummaryView ok = aggregator().summary(tenantToken("LENDING", "SALES"), null);
        assertEquals("Clean Code", ok.sales().get("lowStockItems").get(0).get("titleName").asText());

        when(stats.salesSummary(any())).thenReturn(json("{\"ordersToday\":1,\"lowStockItems\":["
                + "{\"titleId\":\"" + TITLE + "\",\"available\":0,\"threshold\":5}]}"));
        when(titleClient.names(any())).thenThrow(client(HttpStatus.FORBIDDEN, "FORBIDDEN"));
        SummaryView denied = aggregator().summary(tenantToken("LENDING", "SALES"), null);
        assertTrue(denied.sales().get("lowStockItems").get(0).get("titleName").isNull());
        assertEquals(1, denied.sales().get("ordersToday").asInt(), "lỗi tra tên không làm hỏng summary");
    }
}

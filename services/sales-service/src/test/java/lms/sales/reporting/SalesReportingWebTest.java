package lms.sales.reporting;

import static lms.sales.support.WebTokens.admin;
import static lms.sales.support.WebTokens.noPermission;
import static lms.sales.support.WebTokens.withKey;
import static lms.sales.support.WebTokens.withKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lms.sales.config.SecurityConfiguration;
import lms.sales.reporting.application.OrdersCsvReportUseCase;
import lms.sales.reporting.application.SalesStatsQueryPort.SoldTitle;
import lms.sales.reporting.application.SalesStatsUseCases;
import lms.sales.reporting.application.StockCsvReportUseCase;
import lms.sales.reporting.infrastructure.web.SalesReportController;
import lms.sales.reporting.infrastructure.web.SalesStatsController;
import lms.sales.stock.application.CatalogLookupPort;
import lms.sales.support.Harness;
import lms.sales.support.HarnessBeans;
import lms.sales.support.WebTokens;
import lms.security.permission.PermissionAutoConfiguration;
import lms.security.permission.PermissionKeyRegistrar;
import lms.tenant.TenantAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Controller thống kê/báo cáo bán hàng (ADR-0018): ma trận khóa quyền (đã bật lại kiểm khóa), module SALES, hợp đồng
 * JSON và header/BOM của CSV. Use case thật, cổng đọc và tồn kho là bản giả trong bộ nhớ.
 */
@WebMvcTest({SalesStatsController.class, SalesReportController.class})
@Import({SecurityConfiguration.class, PermissionAutoConfiguration.class, TenantAutoConfiguration.class,
        HarnessBeans.class, SalesReportingWebTest.Beans.class})
@TestPropertySource(properties = {"app.security.enabled=true", "lms.security.permission.enabled=true"})
class SalesReportingWebTest {

    private static final String B = "/internal/sales/reporting";
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @TestConfiguration
    static class Beans {
        static final ReportingFakes.StatsQuery QUERY = new ReportingFakes.StatsQuery();
        static final ReportingFakes.OrderReport ORDERS = new ReportingFakes.OrderReport();
        static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-17T05:00:00Z"), ZoneOffset.UTC);

        @Bean
        SalesStatsUseCases salesStatsUseCases(Harness h) {
            return new SalesStatsUseCases(QUERY, h.stockPort, h.getSettings, h.catalog, CLOCK);
        }

        /** Trần 2 dòng để kiểm X-Report-Truncated. */
        @Bean
        StockCsvReportUseCase stockCsvReportUseCase(Harness h) {
            return new StockCsvReportUseCase(h.stockPort, h.catalog, h.getSettings, CLOCK, 2);
        }

        @Bean
        OrdersCsvReportUseCase ordersCsvReportUseCase() {
            return new OrdersCsvReportUseCase(ORDERS, CLOCK, 1);
        }
    }

    record Endpoint(String url, String key) {
    }

    static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint(B + "/summary", "SalesStatsController/summary"),
            new Endpoint(B + "/revenue-trend", "SalesStatsController/revenueTrend"),
            new Endpoint(B + "/top-titles", "SalesStatsController/topSoldTitles"),
            new Endpoint(B + "/stock.csv", "SalesReportController/stockCsv"),
            new Endpoint(B + "/orders.csv", "SalesReportController/ordersCsv"));

    @Autowired
    MockMvc mvc;

    @Autowired
    Harness h;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    PermissionKeyRegistrar permissionKeyRegistrar;

    private int call(Endpoint e, RequestPostProcessor token) throws Exception {
        return mvc.perform(get(e.url()).with(token)).andReturn().getResponse().getStatus();
    }

    // ===== phân quyền =====

    @Test
    void khongTokenBi401() throws Exception {
        for (Endpoint e : ENDPOINTS) {
            mvc.perform(get(e.url())).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void thieuKhoaBi403() throws Exception {
        for (Endpoint e : ENDPOINTS) {
            mvc.perform(get(e.url()).with(noPermission())).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void dungKhoaThiQuaKhoaCuaEndpointKhacBi403KhoaXemTachKhoaXuat() throws Exception {
        for (int i = 0; i < ENDPOINTS.size(); i++) {
            Endpoint e = ENDPOINTS.get(i);
            assertThat(call(e, withKey(e.key()))).as(e.key()).isEqualTo(200);
            Endpoint other = ENDPOINTS.get((i + 1) % ENDPOINTS.size());
            assertThat(call(e, withKey(other.key()))).as(e.key() + " với khóa " + other.key()).isEqualTo(403);
        }
        // role chỉ có khóa xem dashboard không xuất được CSV
        assertThat(call(ENDPOINTS.get(3), withKey("SalesStatsController/summary"))).isEqualTo(403);
        assertThat(call(ENDPOINTS.get(4), withKey("SalesStatsController/revenueTrend"))).isEqualTo(403);
    }

    @Test
    void adminTenantQuaKhiCoModuleSales() throws Exception {
        for (Endpoint e : ENDPOINTS) {
            assertThat(call(e, admin())).as(e.key()).isEqualTo(200);
        }
    }

    @Test
    void thieuModuleSalesBi403KeCaAdmin() throws Exception {
        for (Endpoint e : ENDPOINTS) {
            for (RequestPostProcessor token : List.of(withKeys(WebTokens.TENANT, List.of("LENDING"), e.key()),
                    admin(List.of("LENDING")))) {
                mvc.perform(get(e.url()).with(token)).andExpect(status().isForbidden())
                        .andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
            }
        }
    }

    @Test
    void tokenKhongCoTenantBi403TenantRequired() throws Exception {
        for (Endpoint e : ENDPOINTS) {
            mvc.perform(get(e.url()).with(withKeys(null, List.of("SALES"), e.key())))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
        }
    }

    // ===== JSON =====

    @Test
    void summaryTraDuTruongVaNhanAsOf() throws Exception {
        Beans.QUERY.pendingOnline = 3;
        Beans.QUERY.paid.clear();
        Beans.QUERY.paid.add(new ReportingFakes.Paid(Instant.parse("2026-11-14T03:00:00Z"), 80_000));
        Beans.QUERY.refunded.clear();
        Beans.QUERY.refunded.add(new ReportingFakes.Refunded(Instant.parse("2026-11-14T04:00:00Z"), 30_000));
        mvc.perform(get(B + "/summary").param("asOf", "2026-11-14").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.asOf").value("2026-11-14"))
                .andExpect(jsonPath("$.timezone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.ordersToday").value(1))
                .andExpect(jsonPath("$.grossToday").value(80_000))
                .andExpect(jsonPath("$.refundsToday").value(30_000))
                .andExpect(jsonPath("$.revenueToday").value(50_000))
                .andExpect(jsonPath("$.pendingOnlineOrders").value(3))
                .andExpect(jsonPath("$.lowStockItems").isArray());
    }

    @Test
    void revenueTrendTraDuNgayVaTotals() throws Exception {
        Beans.QUERY.paid.clear();
        Beans.QUERY.refunded.clear();
        Beans.QUERY.paid.add(new ReportingFakes.Paid(Instant.parse("2026-11-16T16:30:00Z"), 10_000));
        mvc.perform(get(B + "/revenue-trend").param("from", "2026-11-16").param("to", "2026-11-17").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.from").value("2026-11-16"))
                .andExpect(jsonPath("$.points.length()").value(2))
                .andExpect(jsonPath("$.points[0].date").value("2026-11-16"))
                .andExpect(jsonPath("$.points[0].gross").value(10_000))
                .andExpect(jsonPath("$.points[1].gross").value(0))
                .andExpect(jsonPath("$.totals.gross").value(10_000))
                .andExpect(jsonPath("$.totals.refunds").value(0))
                .andExpect(jsonPath("$.totals.net").value(10_000))
                .andExpect(jsonPath("$.totals.orders").value(1));
    }

    @Test
    void khoangSaiTra400() throws Exception {
        mvc.perform(get(B + "/revenue-trend").param("from", "2026-11-17").param("to", "2026-11-01").with(admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mvc.perform(get(B + "/revenue-trend").param("from", "2025-01-01").param("to", "2026-11-01").with(admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RANGE_TOO_LARGE"));
        mvc.perform(get(B + "/revenue-trend").param("from", "khong-phai-ngay").with(admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void topTitlesTraTheoQuantityVaKiemLimit() throws Exception {
        Beans.QUERY.sold.clear();
        UUID a = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        Beans.QUERY.sold.add(new SoldTitle(a, "Sach A", 3, 300));
        Beans.QUERY.sold.add(new SoldTitle(b, "Sach B", 9, 900));
        mvc.perform(get(B + "/top-titles").param("limit", "5").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].titleName").value("Sach B"))
                .andExpect(jsonPath("$.items[0].quantity").value(9))
                .andExpect(jsonPath("$.items[0].revenue").value(900));
        mvc.perform(get(B + "/top-titles").param("limit", "0").with(admin())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get(B + "/top-titles").param("limit", "51").with(admin())).andExpect(status().isBadRequest());
    }

    // ===== CSV =====

    private static boolean seeded;

    private synchronized void seedStock() {
        if (seeded) {
            return;
        }
        seeded = true;
        for (int i = 0; i < 3; i++) {
            UUID id = UUID.randomUUID();
            h.catalogTitles.put(id, new CatalogLookupPort.CatalogTitle(id, "Sach " + i, "TG", "ISBN" + i,
                    BigDecimal.valueOf(50_000)));
            h.registerItem.execute(WebTokens.TENANT, WebTokens.USER, id, null, null);
            h.adjust.execute(WebTokens.TENANT, WebTokens.USER, id, i == 0 ? 1 : 100, "ton dau");
        }
    }

    @Test
    void stockCsvCoBomHeaderDungVaCatKhiVuotTran() throws Exception {
        seedStock(); // 3 mặt hàng, trần 2: bị cắt
        var res = mvc.perform(get(B + "/stock.csv").with(admin())).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv; charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"stock-20261117.csv\""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Report-Truncated", "true")).andReturn().getResponse();
        byte[] body = res.getContentAsByteArray();
        assertThat(java.util.Arrays.copyOf(body, 3)).isEqualTo(BOM);
        String[] lines = new String(body, 3, body.length - 3, StandardCharsets.UTF_8).split("\r\n");
        assertThat(lines[0]).startsWith("titleId,titleName,isbn,onHand,");
        assertThat(lines).hasSize(3); // tiêu đề + đúng 2 dòng
    }

    @Test
    void stockCsvLowStockOnlyKhongBiCatThiKhongCoHeaderTruncated() throws Exception {
        seedStock(); // chỉ 1 mặt hàng tồn thấp (tồn 1 <= 5)
        var res = mvc.perform(get(B + "/stock.csv").param("lowStockOnly", "true").with(admin()))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(res.getHeader("X-Report-Truncated")).isNull();
        byte[] body = res.getContentAsByteArray();
        String[] lines = new String(body, 3, body.length - 3, StandardCharsets.UTF_8).split("\r\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[1]).contains(",true,");
    }

    @Test
    void ordersCsvCatTheoTranVaKhongCoThongTinKhach() throws Exception {
        Beans.ORDERS.rows.clear();
        Beans.ORDERS.rows.add(ReportingFakes.OrderReport.row(1));
        Beans.ORDERS.rows.add(ReportingFakes.OrderReport.row(2));
        var res = mvc.perform(get(B + "/orders.csv").param("from", "2026-11-01").param("to", "2026-11-17")
                        .param("channel", "POS").with(admin())).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"orders-20261117.csv\""))
                .andExpect(header().string("X-Report-Truncated", "true")).andReturn().getResponse();
        byte[] body = res.getContentAsByteArray();
        assertThat(java.util.Arrays.copyOf(body, 3)).isEqualTo(BOM);
        String[] lines = new String(body, 3, body.length - 3, StandardCharsets.UTF_8).split("\r\n");
        assertThat(lines).hasSize(2); // trần 1 dòng
        assertThat(lines[0].toLowerCase()).doesNotContain("name", "phone", "address");
        assertThat(Beans.ORDERS.lastFilter.channel()).isEqualTo("POS");
    }

    @Test
    void ordersCsvThamSoSaiTraJsonLoiTruocKhiGhiByte() throws Exception {
        mvc.perform(get(B + "/orders.csv").param("channel", "MOBILE").with(admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get(B + "/orders.csv").param("from", "2026-11-17").param("to", "2026-11-01").with(admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
    }
}

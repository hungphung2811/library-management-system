package lms.api.dashboard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.api.config.SecurityConfiguration;
import lms.api.dashboard.client.CatalogTitleClient;
import lms.api.dashboard.client.DashboardStatsClient;
import lms.api.dashboard.dto.ChartQuery;
import lms.api.dashboard.dto.RangeQuery;
import lms.api.dashboard.dto.SummaryView;
import lms.api.dashboard.dto.TopQuery;
import lms.api.dashboard.service.DashboardAggregator;
import lms.api.dashboard.service.DashboardException;
import lms.api.dashboard.service.TitleNameEnricher;
import lms.api.dashboard.web.DashboardController;
import lms.api.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/** Hợp đồng FE của /api/dashboard/**: xác thực, ánh xạ query, chuyển nguyên lỗi, bổ sung tên đầu sách. */
@WebMvcTest(DashboardController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class, DashboardControllerTest.Wiring.class})
class DashboardControllerTest {

    @TestConfiguration
    static class Wiring {
        @Bean
        TitleNameEnricher enricher(CatalogTitleClient titles) {
            return new TitleNameEnricher(titles);
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID T1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID T2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired MockMvc mvc;
    @MockitoBean DashboardAggregator aggregator;
    @MockitoBean DashboardStatsClient stats;
    @MockitoBean CatalogTitleClient titleClient;
    @MockitoBean JwtDecoder jwtDecoder;

    private static HttpClientErrorException downstream(HttpStatus status, String code) {
        String body = "{\"code\":\"" + code + "\",\"message\":\"x\",\"details\":[]}";
        return HttpClientErrorException.create(status, "err", HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    @Test
    void khongTokenBi401ChoMoiEndpoint() throws Exception {
        for (String path : List.of("/api/dashboard/summary", "/api/dashboard/charts/borrowings",
                "/api/dashboard/charts/revenue", "/api/dashboard/top-titles/borrowed", "/api/dashboard/top-titles/sold")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(aggregator, stats);
    }

    @Test
    void summaryChuyenJwtVaHeaderXTenantIdChoAggregator() throws Exception {
        when(aggregator.summary(any(), any())).thenReturn(new SummaryView(OffsetDateTime.parse("2026-11-17T10:00:00+07:00"),
                "Asia/Ho_Chi_Minh", "22222222-2222-2222-2222-222222222222", List.of("LENDING"),
                JSON.readTree("{\"titles\":60}"), null, null, null, List.of("SALES"), List.of(), List.of()));

        mvc.perform(get("/api/dashboard/summary").header("X-Tenant-Id", "22222222-2222-2222-2222-222222222222")
                        .with(jwt().jwt(j -> j.tokenValue("tok").claim("platformAdmin", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timezone").value("Asia/Ho_Chi_Minh"))
                .andExpect(jsonPath("$.tenantId").value("22222222-2222-2222-2222-222222222222"))
                .andExpect(jsonPath("$.catalog.titles").value(60))
                .andExpect(jsonPath("$.lending").value((Object) null))
                .andExpect(jsonPath("$.sales").value((Object) null))
                .andExpect(jsonPath("$.disabledModules[0]").value("SALES"))
                .andExpect(jsonPath("$.generatedAt").value("2026-11-17T10:00:00+07:00"));

        ArgumentCaptor<Jwt> jwt = ArgumentCaptor.forClass(Jwt.class);
        verify(aggregator).summary(jwt.capture(), eq("22222222-2222-2222-2222-222222222222"));
        org.junit.jupiter.api.Assertions.assertEquals("tok", jwt.getValue().getTokenValue());
    }

    @Test
    void summaryKhongTenantIdVaKhongNullTenantIdTrongJson() throws Exception {
        when(aggregator.summary(any(), any())).thenReturn(new SummaryView(OffsetDateTime.parse("2026-11-17T10:00:00+07:00"),
                "Asia/Ho_Chi_Minh", null, List.of("LENDING", "SALES"), null, null, null, null, List.of(), List.of(),
                List.of()));

        mvc.perform(get("/api/dashboard/summary").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").doesNotExist());
        verify(aggregator).summary(any(), eq(null));
    }

    @Test
    void summaryTraNguyenMaLoiCuaAggregator() throws Exception {
        when(aggregator.summary(any(), any()))
                .thenThrow(new DashboardException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Không có quyền"))
                .thenThrow(new DashboardException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "Tạm ngưng"))
                .thenThrow(downstream(HttpStatus.FORBIDDEN, "TENANT_REQUIRED"));

        mvc.perform(get("/api/dashboard/summary").with(jwt()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/dashboard/summary").with(jwt()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        mvc.perform(get("/api/dashboard/summary").with(jwt()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("TENANT_REQUIRED"));
    }

    @Test
    void bieuDoMuonTraAnhXaQueryVaChuyenNguyenThan() throws Exception {
        when(stats.borrowingTrend(any())).thenReturn(JSON.readTree(
                "{\"from\":\"2026-10-19\",\"to\":\"2026-11-17\",\"granularity\":\"week\",\"points\":[{\"bucket\":\"2026-10-19\",\"borrowed\":3,\"returned\":1}]}"));

        mvc.perform(get("/api/dashboard/charts/borrowings").queryParam("from", "2026-10-19")
                        .queryParam("to", "2026-11-17").queryParam("granularity", "week").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granularity").value("week"))
                .andExpect(jsonPath("$.points[0].borrowed").value(3));

        verify(stats).borrowingTrend(new ChartQuery("2026-10-19", "2026-11-17", "week"));
    }

    @Test
    void bieuDoDoanhThuVaTopBanAnhXaQuery() throws Exception {
        when(stats.revenueTrend(any())).thenReturn(JSON.readTree("{\"totals\":{\"gross\":10,\"refunds\":2,\"net\":8,\"orders\":1},\"points\":[]}"));
        when(stats.topSold(any())).thenReturn(JSON.readTree("{\"items\":[{\"titleId\":\"" + T1 + "\",\"titleName\":\"A\",\"quantity\":3,\"revenue\":30}]}"));

        mvc.perform(get("/api/dashboard/charts/revenue").queryParam("from", "2026-11-01").queryParam("to", "2026-11-17")
                        .with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totals.net").value(8));
        mvc.perform(get("/api/dashboard/top-titles/sold").queryParam("limit", "5").with(jwt()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].quantity").value(3));

        verify(stats).revenueTrend(new RangeQuery("2026-11-01", "2026-11-17"));
        verify(stats).topSold(new TopQuery(null, null, "5"));
        verifyNoInteractions(titleClient); // top bán dùng tên snapshot của sales, BFF không tra
    }

    @Test
    void topMuonBoSungTenTuCatalogVaDauSachThieuThiNull() throws Exception {
        when(stats.topBorrowed(any())).thenReturn(JSON.readTree("{\"items\":[{\"titleId\":\"" + T1 + "\",\"count\":9},"
                + "{\"titleId\":\"" + T2 + "\",\"count\":4}]}"));
        when(titleClient.names(any())).thenReturn(Map.of(T1, "Clean Code"));

        mvc.perform(get("/api/dashboard/top-titles/borrowed").queryParam("limit", "2").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].titleName").value("Clean Code"))
                .andExpect(jsonPath("$.items[0].count").value(9))
                .andExpect(jsonPath("$.items[1].titleName").value((Object) null))
                .andExpect(jsonPath("$.items[1].count").value(4));
    }

    @Test
    void topMuonCatalogLoiHoacThieuKhoaThiTitleNameNullKhongHong() throws Exception {
        when(stats.topBorrowed(any())).thenReturn(JSON.readTree("{\"items\":[{\"titleId\":\"" + T1 + "\",\"count\":9}]}"));
        when(titleClient.names(any())).thenThrow(downstream(HttpStatus.FORBIDDEN, "FORBIDDEN"));

        mvc.perform(get("/api/dashboard/top-titles/borrowed").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].titleName").value((Object) null))
                .andExpect(jsonPath("$.items[0].count").value(9));
    }

    @Test
    void loiKhoangNgayCuaServiceDuocChuyenNguyen() throws Exception {
        when(stats.borrowingTrend(any())).thenThrow(downstream(HttpStatus.BAD_REQUEST, "INVALID_RANGE"));
        when(stats.revenueTrend(any())).thenThrow(downstream(HttpStatus.BAD_REQUEST, "RANGE_TOO_LARGE"));
        when(stats.topSold(any())).thenThrow(downstream(HttpStatus.FORBIDDEN, "MODULE_NOT_ENABLED"));

        mvc.perform(get("/api/dashboard/charts/borrowings").with(jwt()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mvc.perform(get("/api/dashboard/charts/revenue").with(jwt()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RANGE_TOO_LARGE"));
        mvc.perform(get("/api/dashboard/top-titles/sold").with(jwt()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MODULE_NOT_ENABLED"));
    }

    @Test
    void nguon5xxThanh502DependencyError() throws Exception {
        when(stats.revenueTrend(any())).thenThrow(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "x",
                HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

        mvc.perform(get("/api/dashboard/charts/revenue").with(jwt()))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("DEPENDENCY_ERROR"));
    }
}

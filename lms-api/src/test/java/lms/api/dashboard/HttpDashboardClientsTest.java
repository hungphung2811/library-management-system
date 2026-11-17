package lms.api.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lms.api.dashboard.client.http.HttpCatalogTitleClient;
import lms.api.dashboard.client.http.HttpDashboardStatsClient;
import lms.api.dashboard.client.http.HttpPlatformModulesClient;
import lms.api.dashboard.dto.ChartQuery;
import lms.api.dashboard.dto.RangeQuery;
import lms.api.dashboard.dto.TopQuery;
import lms.api.support.TokenRelayInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Các client HTTP của dashboard gọi đúng đường dẫn/query của hợp đồng ADR-0018 mục 2.5 và chuyển token, X-Tenant-Id. */
class HttpDashboardClientsTest {

    private final RestClient.Builder catalogB = builder("http://catalog");
    private final RestClient.Builder circulationB = builder("http://circulation");
    private final RestClient.Builder salesB = builder("http://sales");
    private final MockRestServiceServer catalog = MockRestServiceServer.bindTo(catalogB).build();
    private final MockRestServiceServer circulation = MockRestServiceServer.bindTo(circulationB).build();
    private final MockRestServiceServer sales = MockRestServiceServer.bindTo(salesB).build();
    private final HttpDashboardStatsClient stats = new HttpDashboardStatsClient(catalogB.build(), circulationB.build(),
            salesB.build());

    private static RestClient.Builder builder(String base) {
        return RestClient.builder().baseUrl(base).requestInterceptor(new TokenRelayInterceptor());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private static void asPlatformAdmin(String tenantHeader) {
        Jwt jwt = Jwt.withTokenValue("token-pa").header("alg", "none").subject("pa").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (tenantHeader != null) {
            request.addHeader("X-Tenant-Id", tenantHeader);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void summaryGoiDungDuongDanChuyenTokenVaXTenantId() {
        asPlatformAdmin("22222222-2222-2222-2222-222222222222");
        catalog.expect(requestTo("http://catalog/internal/reporting/catalog/summary"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-pa"))
                .andExpect(header("X-Tenant-Id", "22222222-2222-2222-2222-222222222222"))
                .andRespond(withSuccess("{\"titles\":60}", MediaType.APPLICATION_JSON));
        circulation.expect(requestTo("http://circulation/internal/reporting/lending/summary?asOf=2026-11-17"))
                .andExpect(header("Authorization", "Bearer token-pa"))
                .andExpect(header("X-Tenant-Id", "22222222-2222-2222-2222-222222222222"))
                .andRespond(withSuccess("{\"lending\":{},\"fines\":{}}", MediaType.APPLICATION_JSON));
        sales.expect(requestTo("http://sales/internal/sales/reporting/summary?asOf=2026-11-17"))
                .andExpect(header("Authorization", "Bearer token-pa"))
                .andExpect(header("X-Tenant-Id", "22222222-2222-2222-2222-222222222222"))
                .andRespond(withSuccess("{\"ordersToday\":2}", MediaType.APPLICATION_JSON));

        assertEquals(60, stats.catalogSummary().get("titles").asInt());
        assertTrue(stats.lendingSummary(LocalDate.of(2026, 11, 17)).has("fines"));
        assertEquals(2, stats.salesSummary(LocalDate.of(2026, 11, 17)).get("ordersToday").asInt());
        catalog.verify();
        circulation.verify();
        sales.verify();
    }

    @Test
    void khongCoHeaderXTenantIdThiKhongGuiHeaderNay() {
        asPlatformAdmin(null);
        catalog.expect(requestTo("http://catalog/internal/reporting/catalog/summary"))
                .andExpect(headerDoesNotExist("X-Tenant-Id"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        stats.catalogSummary();
        catalog.verify();
    }

    @Test
    void bieuDoVaTopGoiDungDuongDanVaChiGuiThamSoCoGiaTri() {
        circulation.expect(requestTo("http://circulation/internal/reporting/lending/borrowing-trend?from=2026-10-19&to=2026-11-17&granularity=week"))
                .andRespond(withSuccess("{\"points\":[]}", MediaType.APPLICATION_JSON));
        circulation.expect(requestTo("http://circulation/internal/reporting/lending/borrowing-trend"))
                .andRespond(withSuccess("{\"points\":[]}", MediaType.APPLICATION_JSON));
        circulation.expect(requestTo("http://circulation/internal/reporting/lending/top-titles?from=2026-11-01&to=2026-11-17&limit=5"))
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));
        sales.expect(requestTo("http://sales/internal/sales/reporting/revenue-trend?from=2026-11-01&to=2026-11-17"))
                .andRespond(withSuccess("{\"points\":[]}", MediaType.APPLICATION_JSON));
        sales.expect(requestTo("http://sales/internal/sales/reporting/top-titles?limit=10"))
                .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        stats.borrowingTrend(new ChartQuery("2026-10-19", "2026-11-17", "week"));
        stats.borrowingTrend(new ChartQuery(null, "", null));
        stats.topBorrowed(new TopQuery("2026-11-01", "2026-11-17", "5"));
        stats.revenueTrend(new RangeQuery("2026-11-01", "2026-11-17"));
        stats.topSold(new TopQuery(null, null, "10"));
        circulation.verify();
        sales.verify();
    }

    @Test
    void loi4xxVaLoi5xxDuocNemRaChoTangTrenXuLy() {
        circulation.expect(requestTo("http://circulation/internal/reporting/lending/borrowing-trend"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"INVALID_RANGE\"}"));
        sales.expect(requestTo("http://sales/internal/sales/reporting/revenue-trend"))
                .andRespond(withServerError());

        HttpClientErrorException ex = assertThrows(HttpClientErrorException.class,
                () -> stats.borrowingTrend(new ChartQuery(null, null, null)));
        assertTrue(ex.getResponseBodyAsString().contains("INVALID_RANGE"));
        assertThrows(HttpServerErrorException.class, () -> stats.revenueTrend(new RangeQuery(null, null)));
    }

    @Test
    void tracuuTenDauSachTheoLoPostBodyIdsVaBoQuaTruongLa() {
        UUID a = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID b = UUID.fromString("22222222-2222-2222-2222-222222222222");
        catalog.expect(requestTo("http://catalog/internal/books/lookup")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.ids[0]").value(a.toString())).andExpect(jsonPath("$.ids[1]").value(b.toString()))
                .andRespond(withSuccess("[{\"id\":\"" + a + "\",\"name\":\"Clean Code\",\"isbn\":null,\"coverPrice\":1}]",
                        MediaType.APPLICATION_JSON));

        Map<UUID, String> names = new HttpCatalogTitleClient(catalogB.build()).names(List.of(a, b));

        assertEquals(Map.of(a, "Clean Code"), names);
        catalog.verify();
    }

    @Test
    void tracuuModuleCuaTenantDichChiLayModuleDangBat() {
        asPlatformAdmin("22222222-2222-2222-2222-222222222222");
        RestClient.Builder identityB = builder("http://identity");
        MockRestServiceServer identity = MockRestServiceServer.bindTo(identityB).build();
        identity.expect(requestTo("http://identity/api/platform/tenants/22222222-2222-2222-2222-222222222222/modules"))
                .andExpect(header("Authorization", "Bearer token-pa"))
                .andRespond(withSuccess("[{\"module\":\"LENDING\",\"enabled\":true,\"updatedAt\":null},"
                        + "{\"module\":\"SALES\",\"enabled\":false,\"updatedAt\":null}]", MediaType.APPLICATION_JSON));

        Set<String> enabled = new HttpPlatformModulesClient(identityB.build())
                .enabledModules(UUID.fromString("22222222-2222-2222-2222-222222222222"));

        assertEquals(Set.of("LENDING"), enabled);
        identity.verify();
    }
}

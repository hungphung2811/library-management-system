package lms.api.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lms.api.config.SecurityConfiguration;
import lms.api.dashboard.client.CatalogTitleClient;
import lms.api.dashboard.client.DashboardStatsClient;
import lms.api.dashboard.client.PlatformModulesClient;
import lms.api.dashboard.client.http.HttpCatalogTitleClient;
import lms.api.dashboard.client.http.HttpDashboardStatsClient;
import lms.api.dashboard.client.http.HttpPlatformModulesClient;
import lms.api.dashboard.service.DashboardAggregator;
import lms.api.dashboard.service.DashboardExecutor;
import lms.api.dashboard.service.ModuleResolver;
import lms.api.dashboard.service.TitleNameEnricher;
import lms.api.dashboard.web.DashboardController;
import lms.api.support.TokenRelayInterceptor;
import lms.api.web.ApiExceptionHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

/**
 * Test quan trọng nhất của nhóm dashboard (ADR-0018 mục 5): summary gọi song song nên TokenRelayInterceptor phải
 * có token và X-Tenant-Id trên luồng con. Dùng stack thật (MockMvc, aggregator, executor có TaskDecorator, RestClient
 * có TokenRelayInterceptor) nối tới máy chủ HTTP thật ghi lại header từng nguồn.
 */
@WebMvcTest(DashboardController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class, DashboardParallelRelayTest.Wiring.class})
class DashboardParallelRelayTest {

    static final FakeServices SERVICES = new FakeServices();
    static final String TITLE = "11111111-1111-1111-1111-111111111111";
    static final String TARGET_TENANT = "22222222-2222-2222-2222-222222222222";

    @TestConfiguration
    static class Wiring {
        private static RestClient client() {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(1000);
            factory.setReadTimeout(5000);
            return RestClient.builder().baseUrl(SERVICES.baseUrl()).requestFactory(factory)
                    .requestInterceptor(new TokenRelayInterceptor()).build();
        }

        @Bean
        DashboardStatsClient stats() {
            RestClient c = client();
            return new HttpDashboardStatsClient(c, c, c);
        }

        @Bean
        CatalogTitleClient titleClient() {
            return new HttpCatalogTitleClient(client());
        }

        @Bean
        PlatformModulesClient modulesClient() {
            return new HttpPlatformModulesClient(client());
        }

        @Bean
        DashboardExecutor executor() {
            return new DashboardExecutor(6, 12, 24);
        }

        @Bean
        ModuleResolver resolver(PlatformModulesClient modules) {
            return new ModuleResolver(modules);
        }

        @Bean
        TitleNameEnricher enricher(CatalogTitleClient titles) {
            return new TitleNameEnricher(titles);
        }

        @Bean
        DashboardAggregator aggregator(DashboardStatsClient stats, ModuleResolver resolver, TitleNameEnricher enricher,
                                       DashboardExecutor executor) {
            return new DashboardAggregator(stats, resolver, enricher, executor, Clock.systemUTC(), Duration.ofSeconds(3));
        }
    }

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder jwtDecoder;

    @AfterAll
    static void stop() {
        SERVICES.close();
    }

    @BeforeEach
    void routes() {
        SERVICES.reset();
        // ba nguồn chỉ trả lời khi cả ba cùng có mặt: chứng minh gọi song song thật sự (tuần tự sẽ hết hạn và trả 500)
        CountDownLatch together = new CountDownLatch(3);
        java.util.function.Function<String, java.util.function.Function<com.sun.net.httpserver.HttpExchange, FakeServices.Reply>> rendezvous =
                body -> ex -> {
                    together.countDown();
                    try {
                        return together.await(2, TimeUnit.SECONDS) ? FakeServices.Reply.json(body)
                                : FakeServices.Reply.error(500, "NOT_PARALLEL");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return FakeServices.Reply.error(500, "INTERRUPTED");
                    }
                };
        SERVICES.route("GET /internal/reporting/catalog/summary",
                        rendezvous.apply("{\"titles\":60,\"copies\":{\"total\":150}}"))
                .route("GET /internal/reporting/lending/summary",
                        rendezvous.apply("{\"lending\":{\"overdueBorrowings\":6},\"fines\":{\"unpaidCount\":4}}"))
                .route("GET /internal/sales/reporting/summary", rendezvous.apply(
                        "{\"ordersToday\":2,\"lowStockItems\":[{\"titleId\":\"" + TITLE + "\",\"available\":0,\"threshold\":5}]}"))
                .route("POST /internal/books/lookup",
                        FakeServices.Reply.json("[{\"id\":\"" + TITLE + "\",\"name\":\"Clean Code\"}]"))
                .route("GET /api/platform/tenants/*", FakeServices.Reply.json(
                        "[{\"module\":\"LENDING\",\"enabled\":true},{\"module\":\"SALES\",\"enabled\":true}]"));
    }

    @Test
    void tokenToiDuocCaBaNguonKhiGoiSongSong() throws Exception {
        mvc.perform(get("/api/dashboard/summary").header("X-Correlation-Id", "ignored")
                        .with(jwt().jwt(j -> j.tokenValue("token-nguoi-dung").claim("tenantId", TARGET_TENANT)
                                .claim("modules", List.of("LENDING", "SALES")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalog.titles").value(60))
                .andExpect(jsonPath("$.lending.overdueBorrowings").value(6))
                .andExpect(jsonPath("$.fines.unpaidCount").value(4))
                .andExpect(jsonPath("$.sales.ordersToday").value(2))
                .andExpect(jsonPath("$.sales.lowStockItems[0].titleName").value("Clean Code"))
                .andExpect(jsonPath("$.unavailableSections").isEmpty())
                .andExpect(jsonPath("$.forbiddenSections").isEmpty());

        for (String path : List.of("/internal/reporting/catalog/summary", "/internal/reporting/lending/summary",
                "/internal/sales/reporting/summary", "/internal/books/lookup")) {
            List<FakeServices.Hit> hits = SERVICES.hitsTo(path);
            assertEquals(1, hits.size(), path);
            assertEquals("Bearer token-nguoi-dung", hits.get(0).authorization(), "token tới " + path);
            assertEquals(null, hits.get(0).tenantHeader(), "không có X-Tenant-Id từ người gọi: " + path);
        }
        // người dùng của tenant: không cần tra module của identity
        assertTrue(SERVICES.hitsTo("/api/platform").isEmpty());
    }

    @Test
    void platformAdminXTenantIdToiCaBaNguonVaTraModule() throws Exception {
        mvc.perform(get("/api/dashboard/summary").header("X-Tenant-Id", TARGET_TENANT)
                        .with(jwt().jwt(j -> j.tokenValue("token-platform").claim("platformAdmin", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TARGET_TENANT))
                .andExpect(jsonPath("$.modules[0]").value("LENDING"))
                .andExpect(jsonPath("$.modules[1]").value("SALES"))
                .andExpect(jsonPath("$.catalog.titles").value(60))
                .andExpect(jsonPath("$.lending.overdueBorrowings").value(6))
                .andExpect(jsonPath("$.sales.ordersToday").value(2));

        for (String path : List.of("/internal/reporting/catalog/summary", "/internal/reporting/lending/summary",
                "/internal/sales/reporting/summary", "/internal/books/lookup",
                "/api/platform/tenants/" + TARGET_TENANT + "/modules")) {
            List<FakeServices.Hit> hits = SERVICES.hitsTo(path);
            assertEquals(1, hits.size(), path);
            assertEquals("Bearer token-platform", hits.get(0).authorization(), "token tới " + path);
            assertEquals(TARGET_TENANT, hits.get(0).tenantHeader(), "X-Tenant-Id tới " + path);
        }
    }

    @Test
    void khongCoTokenKhongGoiNguonNao() throws Exception {
        mvc.perform(get("/api/dashboard/summary")).andExpect(status().isUnauthorized());
        assertFalse(SERVICES.hits().stream().anyMatch(h -> h.path().startsWith("/internal")));
    }
}

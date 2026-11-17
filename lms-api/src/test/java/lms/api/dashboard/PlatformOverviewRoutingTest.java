package lms.api.dashboard;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import lms.api.config.SecurityConfiguration;
import lms.api.identity.client.IdentityGatewayClient;
import lms.api.identity.client.http.HttpIdentityGatewayClient;
import lms.api.identity.web.IdentityGatewayController;
import lms.api.support.TokenRelayInterceptor;
import lms.api.web.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

/**
 * GET /api/platform/dashboard/overview (identity, @PlatformAdminOnly) đã được lms-api chuyển nguyên qua
 * /api/platform/**: không có route riêng, chỉ kiểm định tuyến, query và mã lỗi (ADR-0018 mục 2.1).
 */
@WebMvcTest(IdentityGatewayController.class)
@Import({SecurityConfiguration.class, ApiExceptionHandler.class, PlatformOverviewRoutingTest.IdentityStub.class})
class PlatformOverviewRoutingTest {

    @TestConfiguration
    static class IdentityStub {
        static final String BASE = "http://identity";
        private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE)
                .requestInterceptor(new TokenRelayInterceptor());
        private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        @Bean
        MockRestServiceServer identityServer() {
            return server;
        }

        @Bean
        IdentityGatewayClient identityGatewayClient() {
            return new HttpIdentityGatewayClient(builder.build(), BASE);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired MockRestServiceServer identity;
    @MockitoBean JwtDecoder jwtDecoder;

    @BeforeEach
    void reset() {
        identity.reset();
    }

    @Test
    void overviewChuyenNguyenToiIdentityVoiQueryVaToken() throws Exception {
        identity.expect(requestTo("http://identity/api/platform/dashboard/overview?withinDays=45"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-platform"))
                .andRespond(withSuccess("{\"tenants\":{\"total\":3,\"active\":2,\"suspended\":1},"
                        + "\"activeTenantsByModule\":{\"LENDING\":3,\"SALES\":1},\"withinDays\":45,"
                        + "\"expiringContractCount\":1,\"expiringContracts\":[]}", MediaType.APPLICATION_JSON));

        mvc.perform(get("/api/platform/dashboard/overview").queryParam("withinDays", "45")
                        .with(jwt().jwt(j -> j.tokenValue("token-platform"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenants.total").value(3))
                .andExpect(jsonPath("$.activeTenantsByModule.SALES").value(1))
                .andExpect(jsonPath("$.withinDays").value(45));
        identity.verify();
    }

    @Test
    void khongPhaiPlatformAdminNhan403CuaIdentityNguyenVan() throws Exception {
        identity.expect(requestTo("http://identity/api/platform/dashboard/overview"))
                .andExpect(header("Authorization", "Bearer token-tenant"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"FORBIDDEN\"}"));

        mvc.perform(get("/api/platform/dashboard/overview").with(jwt().jwt(j -> j.tokenValue("token-tenant"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        identity.verify();
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/api/platform/dashboard/overview")).andExpect(status().isUnauthorized());
    }
}

package lms.api;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lms.api.config.SecurityConfiguration;
import lms.api.identity.client.IdentityGatewayClient;
import lms.api.identity.client.http.HttpIdentityGatewayClient;
import lms.api.identity.web.IdentityGatewayController;
import lms.api.support.TokenRelayInterceptor;
import lms.api.support.client.SupportClient;
import lms.api.support.dto.NotificationView;
import lms.api.support.web.NotificationController;
import lms.api.web.ApiExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

/** Định tuyến của lms-api cho API nền tảng, /api/me/tenants và thông báo: đúng đích, giữ nguyên Authorization. */
@WebMvcTest({IdentityGatewayController.class, NotificationController.class})
@Import({SecurityConfiguration.class, ApiExceptionHandler.class, LmsApiRoutingTest.IdentityStub.class})
class LmsApiRoutingTest {

    /** identity-service giả: client thật (có TokenRelayInterceptor) nối vào MockRestServiceServer. */
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
    @MockitoBean SupportClient support;
    @MockitoBean JwtDecoder jwtDecoder;

    @BeforeEach
    void reset() {
        identity.reset();
    }

    @Test
    void platformTenantsChuyenToiIdentityGiuNguyenAuthorization() throws Exception {
        identity.expect(requestTo("http://identity/api/platform/tenants?page=1&q=th%C6%B0%20vi%E1%BB%87n"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer token-platform"))
                .andRespond(withSuccess("[{\"code\":\"abc\"}]", MediaType.APPLICATION_JSON));

        mvc.perform(get("/api/platform/tenants").queryParam("page", "1").queryParam("q", "thư viện")
                        .with(jwt().jwt(j -> j.tokenValue("token-platform"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("abc"));
        identity.verify();
    }

    @Test
    void platformPostChuyenBodyVaTrangThaiCua4xx() throws Exception {
        identity.expect(requestTo("http://identity/api/platform/tenants"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer token-tenant"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().json("{\"code\":\"abc\"}"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"PLATFORM_ADMIN_REQUIRED\"}"));

        mvc.perform(post("/api/platform/tenants").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"abc\"}").with(jwt().jwt(j -> j.tokenValue("token-tenant"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PLATFORM_ADMIN_REQUIRED"));
        identity.verify();
    }

    @Test
    void meTenantsChuyenToiIdentity() throws Exception {
        identity.expect(requestTo("http://identity/internal/me/tenants"))
                .andExpect(header("Authorization", "Bearer token-user"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        mvc.perform(get("/api/me/tenants").with(jwt().jwt(j -> j.tokenValue("token-user"))))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("[]"));
        identity.verify();
    }

    @Test
    void khongTokenBi401() throws Exception {
        mvc.perform(get("/api/platform/tenants")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me/tenants")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/notifications/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void thongBaoCuaToiChuyenToiSupport() throws Exception {
        when(support.myNotifications()).thenReturn(List.of(new NotificationView(UUID.randomUUID(),
                "RESERVATION_READY", "Sách đã sẵn sàng", Instant.parse("2026-01-01T00:00:00Z"), null)));

        mvc.perform(get("/api/notifications/me").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("RESERVATION_READY"));
    }
}

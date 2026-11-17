package lms.customer.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;
import lms.customer.auth.client.http.HttpAuthClient;
import lms.customer.auth.dto.AuthDtos.IdentityTokenView;
import lms.customer.auth.dto.AuthDtos.SignupRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Hợp đồng với identity (stub): kênh STOREFRONT do BFF đặt, signup allowlist, refresh kèm tenantId (ADR-0013 2.4). */
class HttpAuthClientStorefrontTest {

    private static final String TOKENS = "{\"accessToken\":\"a\",\"refreshToken\":\"r\",\"expiresIn\":900}";

    private RestClient.Builder builder() {
        return RestClient.builder().baseUrl("http://identity");
    }

    @Test
    void loginTuDatChannelStorefrontVaTenantCode() {
        RestClient.Builder b = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        HttpAuthClient client = new HttpAuthClient(b.build());
        server.expect(requestTo("http://identity/internal/auth/login")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.channel").value("STOREFRONT"))
                .andExpect(jsonPath("$.tenantCode").value("shop-a"))
                .andRespond(withSuccess(TOKENS, MediaType.APPLICATION_JSON));
        IdentityTokenView view = client.login("shop-a", "an", "mat-khau-1");
        assertEquals("a", view.accessToken());
        server.verify();
    }

    @Test
    void signupGuiDungNamTruongCongTenantCodeVaKhongGiHonNua() {
        RestClient.Builder b = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        HttpAuthClient client = new HttpAuthClient(b.build());
        server.expect(requestTo("http://identity/internal/auth/signup")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"tenantCode\":\"shop-a\",\"username\":\"khach1\",\"password\":\"mat-khau-1\","
                        + "\"fullName\":\"Nguyễn An\",\"email\":\"an@example.vn\"}", true))
                .andRespond(withStatus(HttpStatus.CREATED));
        client.signup("shop-a", new SignupRequest("khach1", "mat-khau-1", "Nguyễn An", "an@example.vn"));
        server.verify();
    }

    @Test
    void refreshTheoCuaHangGuiChannelVaTenantId() {
        RestClient.Builder b = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        HttpAuthClient client = new HttpAuthClient(b.build());
        UUID tenant = UUID.randomUUID();
        server.expect(requestTo("http://identity/internal/auth/refresh"))
                .andExpect(content().json("{\"refreshToken\":\"rt\",\"channel\":\"STOREFRONT\",\"tenantId\":\"" + tenant
                        + "\"}", true))
                .andRespond(withSuccess(TOKENS, MediaType.APPLICATION_JSON));
        client.refresh("rt", tenant);
        server.verify();
    }

    @Test
    void refreshDuongCuKhongCoTenantIdNhungVanCoChannel() {
        RestClient.Builder b = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        HttpAuthClient client = new HttpAuthClient(b.build());
        server.expect(requestTo("http://identity/internal/auth/refresh"))
                .andExpect(content().json("{\"refreshToken\":\"rt\",\"channel\":\"STOREFRONT\"}", true))
                .andRespond(withSuccess(TOKENS, MediaType.APPLICATION_JSON));
        client.refresh("rt");
        server.verify();
    }

    @Test
    void coTatGuiTenantIdChoIdentityCuTuChoiTruongLa() {
        RestClient.Builder b = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        HttpAuthClient client = new HttpAuthClient(b.build(), false);
        server.expect(requestTo("http://identity/internal/auth/refresh"))
                .andExpect(content().json("{\"refreshToken\":\"rt\",\"channel\":\"STOREFRONT\"}", true))
                .andRespond(withSuccess(TOKENS, MediaType.APPLICATION_JSON));
        client.refresh("rt", UUID.randomUUID());
        server.verify();
    }

    @Test
    void logoutChiGuiRefreshToken() {
        RestClient.Builder b = builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(b).build();
        HttpAuthClient client = new HttpAuthClient(b.build());
        server.expect(requestTo("http://identity/internal/auth/logout"))
                .andExpect(content().json("{\"refreshToken\":\"rt\"}", true))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist("Authorization"))
                .andRespond(withNoContent());
        client.logout("rt");
        server.verify();
    }
}

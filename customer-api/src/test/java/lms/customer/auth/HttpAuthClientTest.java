package lms.customer.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import lms.customer.auth.client.http.HttpAuthClient;
import lms.customer.auth.dto.AuthDtos.IdentityTokenView;
import lms.customer.web.PublicApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

class HttpAuthClientTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://identity");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpAuthClient client = new HttpAuthClient(builder.build());

    @Test
    void loginGuiTenantCodeTuDuongDanVaChiLayTruongCanThiet() {
        server.expect(requestTo("http://identity/internal/auth/login")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.tenantCode").value("shop-a"))
                .andExpect(jsonPath("$.username").value("an"))
                .andRespond(withSuccess("{\"accessToken\":\"a\",\"refreshToken\":\"r\",\"tokenType\":\"Bearer\","
                        + "\"expiresIn\":900,\"mustChangePassword\":false,\"availableTenants\":[{\"code\":\"khac\"}]}",
                        MediaType.APPLICATION_JSON));
        IdentityTokenView view = client.login("shop-a", "an", "mat-khau-1");
        assertEquals("a", view.accessToken());
        assertEquals(900, view.expiresIn());
    }

    @Test
    void saiMatKhauVaKhongThuocTenantCungLaLoginFailed() {
        server.expect(requestTo("http://identity/internal/auth/login"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"UNAUTHORIZED\",\"message\":\"x\"}"));
        server.expect(requestTo("http://identity/internal/auth/login"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"NO_TENANT_ACCESS\",\"message\":\"x\"}"));
        for (int i = 0; i < 2; i++) {
            PublicApiException ex = assertThrows(PublicApiException.class, () -> client.login("shop-a", "an", "sai"));
            assertEquals(401, ex.status().value());
            assertEquals("LOGIN_FAILED", ex.code());
        }
    }

    @Test
    void taiKhoanBiKhoaHoacVoHieuCungLaLoginFailedKhiDangNhap() {
        server.expect(requestTo("http://identity/internal/auth/login"))
                .andRespond(withStatus(HttpStatus.LOCKED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"ACCOUNT_LOCKED\",\"message\":\"x\"}"));
        server.expect(requestTo("http://identity/internal/auth/login"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"ACCOUNT_DISABLED\",\"message\":\"x\"}"));
        for (int i = 0; i < 2; i++) {
            PublicApiException ex = assertThrows(PublicApiException.class, () -> client.login("shop-a", "an", "x"));
            assertEquals(401, ex.status().value());
            assertEquals("LOGIN_FAILED", ex.code());
        }
    }

    @Test
    void maLoiKhacCuaLoginVanChuyenTiepNguyenVan() {
        server.expect(requestTo("http://identity/internal/auth/login"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"FORBIDDEN\",\"message\":\"x\"}"));
        assertThrows(RestClientResponseException.class, () -> client.login("shop-a", "an", "x"));
    }
}

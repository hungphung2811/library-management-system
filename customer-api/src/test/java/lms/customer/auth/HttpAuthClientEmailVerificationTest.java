package lms.customer.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import lms.customer.auth.client.http.HttpAuthClient;
import lms.customer.web.PublicApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** HttpAuthClient: gọi đúng đường dẫn identity cho xác minh email và login EMAIL_NOT_VERIFIED không bị gộp LOGIN_FAILED. */
class HttpAuthClientEmailVerificationTest {

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://identity");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final HttpAuthClient client = new HttpAuthClient(builder.build());

    @Test
    void verifyGuiDungDuongDanVaBoTruongNull() {
        server.expect(requestTo("http://identity/internal/auth/email-verifications/confirm"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"username\":\"khach1\",\"otp\":\"123456\"}", true))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.verifyEmail("khach1", null, "123456");

        server.verify();
    }

    @Test
    void resendGuiDungDuongDan() {
        server.expect(requestTo("http://identity/internal/auth/email-verifications/resend"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"email\":\"an@example.vn\"}", true))
                .andRespond(withStatus(HttpStatus.ACCEPTED));

        client.resendVerification(null, "an@example.vn");

        server.verify();
    }

    @Test
    void loginEmailNotVerifiedKhongBiGopThanhLoginFailed() {
        server.expect(requestTo("http://identity/internal/auth/login"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"EMAIL_NOT_VERIFIED\",\"message\":\"x\"}"));

        RestClientResponseException ex = assertThrows(RestClientResponseException.class,
                () -> client.login("shop-a", "an", "mat-khau-1"));

        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void loginSaiMatKhauVanLaLoginFailed() {
        server.expect(requestTo("http://identity/internal/auth/login"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"UNAUTHORIZED\",\"message\":\"x\"}"));

        PublicApiException ex = assertThrows(PublicApiException.class, () -> client.login("shop-a", "an", "sai"));

        assertEquals("LOGIN_FAILED", ex.code());
    }
}

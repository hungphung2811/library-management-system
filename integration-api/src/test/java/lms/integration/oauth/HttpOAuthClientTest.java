package lms.integration.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import lms.integration.oauth.client.OAuthClient.OAuthErrorCode;
import lms.integration.oauth.client.OAuthClient.TokenResult;
import lms.integration.oauth.client.http.HttpOAuthClient;
import lms.integration.oauth.dto.OAuthDtos.TokenRequest;
import lms.integration.web.IntegrationApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Chuyển tiếp form tới identity: allowlist trường, Authorization Basic, chỉ chấp nhận phản hồi RFC trong tập đóng. */
@ExtendWith(OutputCaptureExtension.class)
class HttpOAuthClientTest {

    private static final String URI = "http://identity.test/internal/oauth2/token";
    private static final String SECRET = "lms_sk_bi-mat-thu-nghiem-0123456789abcdefghijk";

    private MockRestServiceServer server;
    private HttpOAuthClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://identity.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new HttpOAuthClient(builder.build(), new ObjectMapper());
    }

    private static TokenRequest basic() {
        return new TokenRequest("Basic dGVzdDpzZWNyZXQ=", "client_credentials", "books:read", null, null);
    }

    @Test
    void chuyenFormVaAuthorizationBasicRoiTraToken() {
        server.expect(requestTo(URI)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic dGVzdDpzZWNyZXQ="))
                .andExpect(header("Content-Type", org.hamcrest.Matchers.startsWith("application/x-www-form-urlencoded")))
                .andExpect(content().string("grant_type=client_credentials&scope=books%3Aread"))
                .andRespond(withSuccess("{\"access_token\":\"jwt\",\"token_type\":\"Bearer\",\"expires_in\":300,"
                        + "\"scope\":\"books:read\",\"extra\":\"bo-qua\"}", MediaType.APPLICATION_JSON));

        TokenResult result = client.requestToken(basic());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.token().accessToken()).isEqualTo("jwt");
        assertThat(result.token().expiresIn()).isEqualTo(300);
        assertThat(result.token().scope()).isEqualTo("books:read");
        server.verify();
    }

    @Test
    void thongTinClientTrongThanDuocChuyenVaKhongCoAuthorization() {
        server.expect(requestTo(URI)).andExpect(request -> assertThat(request.getHeaders().containsKey("Authorization")).isFalse())
                .andExpect(content().string("grant_type=client_credentials&client_id=abc&client_secret=" + SECRET))
                .andRespond(withSuccess("{\"access_token\":\"jwt\",\"token_type\":\"Bearer\",\"expires_in\":300}",
                        MediaType.APPLICATION_JSON));

        TokenResult result = client.requestToken(
                new TokenRequest(null, "client_credentials", null, "abc", SECRET));

        assertThat(result.isSuccess()).isTrue();
        server.verify();
    }

    @Test
    void loiRfcTrongTapDongDuocChuyenDungCap() {
        record Case(HttpStatus status, String error, OAuthErrorCode expected) { }
        for (Case c : new Case[]{
                new Case(HttpStatus.UNAUTHORIZED, "invalid_client", OAuthErrorCode.INVALID_CLIENT),
                new Case(HttpStatus.BAD_REQUEST, "invalid_request", OAuthErrorCode.INVALID_REQUEST),
                new Case(HttpStatus.BAD_REQUEST, "unsupported_grant_type", OAuthErrorCode.UNSUPPORTED_GRANT_TYPE),
                new Case(HttpStatus.BAD_REQUEST, "invalid_scope", OAuthErrorCode.INVALID_SCOPE)}) {
            server.reset();
            server.expect(requestTo(URI)).andRespond(withStatus(c.status())
                    .body("{\"error\":\"" + c.error() + "\",\"error_description\":\"lý do nội bộ\"}")
                    .contentType(MediaType.APPLICATION_JSON));

            TokenResult result = client.requestToken(basic());

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.error()).isEqualTo(c.expected());
        }
    }

    @Test
    void phanHoiLaThanhServiceUnavailableChungKhongLoChiTiet() {
        record Case(HttpStatus status, String body) { }
        for (Case c : new Case[]{
                new Case(HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":\"invalid_client\"}"),
                new Case(HttpStatus.BAD_GATEWAY, "<html>lỗi nội bộ</html>"),
                new Case(HttpStatus.FORBIDDEN, "{\"code\":\"FORBIDDEN\"}"),
                new Case(HttpStatus.NOT_FOUND, ""),
                new Case(HttpStatus.TOO_MANY_REQUESTS, "{}"),
                // mã trạng thái và mã lỗi không khớp, mã lỗi lạ, thân hỏng
                new Case(HttpStatus.UNAUTHORIZED, "{\"error\":\"invalid_scope\"}"),
                new Case(HttpStatus.BAD_REQUEST, "{\"error\":\"invalid_client\"}"),
                new Case(HttpStatus.BAD_REQUEST, "{\"error\":\"server_exploded\"}"),
                new Case(HttpStatus.BAD_REQUEST, "khong phai json"),
                new Case(HttpStatus.OK, "{\"token_type\":\"Bearer\",\"expires_in\":300}"),
                new Case(HttpStatus.OK, "{\"access_token\":\"x\",\"token_type\":\"mac\",\"expires_in\":300}"),
                new Case(HttpStatus.OK, "{\"access_token\":\"x\",\"token_type\":\"Bearer\",\"expires_in\":0}"),
                new Case(HttpStatus.OK, "")}) {
            server.reset();
            server.expect(requestTo(URI)).andRespond(withStatus(c.status()).body(c.body())
                    .contentType(MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> client.requestToken(basic())).as("%s %s", c.status(), c.body())
                    .isInstanceOf(IntegrationApiException.class)
                    .satisfies(e -> {
                        IntegrationApiException ex = (IntegrationApiException) e;
                        assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(ex.code()).isEqualTo("SERVICE_UNAVAILABLE");
                        assertThat(ex.getMessage()).doesNotContain("identity").doesNotContain("nội bộ");
                    });
        }
    }

    @Test
    void matKetNoiThanh503KhongLoSecretVaKhongLog(CapturedOutput output) {
        server.expect(requestTo(URI)).andRespond(request -> {
            throw new IOException("connection refused " + SECRET);
        });

        assertThatThrownBy(() -> client.requestToken(new TokenRequest("Basic dGVzdDpzZWNyZXQ=",
                "client_credentials", null, "abc", SECRET)))
                .isInstanceOf(IntegrationApiException.class)
                .satisfies(e -> assertThat(String.valueOf(e.getMessage())).doesNotContain(SECRET));
        assertThat(output.getAll()).doesNotContain(SECRET).doesNotContain("dGVzdDpzZWNyZXQ=");
    }

    @Test
    void tokenRequestToStringKhongInSecret() {
        assertThat(new TokenRequest("Basic abc", "client_credentials", "books:read", "id", SECRET).toString())
                .doesNotContain(SECRET).doesNotContain("Basic abc");
    }
}

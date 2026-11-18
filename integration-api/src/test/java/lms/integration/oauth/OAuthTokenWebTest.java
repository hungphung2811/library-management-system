package lms.integration.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import lms.integration.config.SecurityConfiguration;
import lms.integration.config.TestTokens;
import lms.integration.oauth.client.OAuthClient;
import lms.integration.oauth.client.OAuthClient.OAuthErrorCode;
import lms.integration.oauth.client.OAuthClient.TokenResult;
import lms.integration.oauth.dto.OAuthDtos.TokenRequest;
import lms.integration.oauth.dto.OAuthDtos.TokenResponse;
import lms.integration.oauth.web.OAuthTokenController;
import lms.integration.web.IntegrationApiException;
import lms.integration.web.IntegrationExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Khung bảo mật và token endpoint của integration-api (pha I2): chỉ health và POST /oauth/token công khai; token sai aud,
 * iss, hết hạn, sai chữ ký đều 401 JSON thống nhất; token integration hợp lệ nhưng chưa có endpoint dữ liệu thì 404; mọi
 * đường dẫn ngoài danh sách đóng là 403; /oauth/token chuyển tiếp giữ ngữ nghĩa RFC 6749 và no-store; không log secret.
 */
@ExtendWith(OutputCaptureExtension.class)
@WebMvcTest(OAuthTokenController.class)
@Import({SecurityConfiguration.class, IntegrationExceptionHandler.class, OAuthTokenWebTest.Keys.class})
class OAuthTokenWebTest {

    private static final String TOKEN = SecurityConfiguration.TOKEN_PATH;
    private static final String CLIENT_ID = "8b0f1f0e-5a43-4c5c-9d0b-111111111111";
    private static final String SECRET = "lms_sk_bi-mat-thu-nghiem-0123456789abcdefghijk";

    @TestConfiguration
    static class Keys {
        static final TestTokens TOKENS = new TestTokens();

        @Bean
        JwtDecoder jwtDecoder() {
            return TOKENS.decoder();
        }
    }

    @Autowired MockMvc mvc;
    @MockitoBean OAuthClient oauth;

    private static MockHttpServletRequestBuilder form(String... pairs) {
        MockHttpServletRequestBuilder b = post(TOKEN).contentType(MediaType.APPLICATION_FORM_URLENCODED);
        for (int i = 0; i < pairs.length; i += 2) b.param(pairs[i], pairs[i + 1]);
        return b;
    }

    private static String basic() {
        return "Basic " + Base64.getEncoder().encodeToString((CLIENT_ID + ":" + SECRET).getBytes(StandardCharsets.UTF_8));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    // ------------------------------------------------------------------ bảo mật

    @Test
    void khongTokenOApiTichHopLa401JsonKemWwwAuthenticate() throws Exception {
        mvc.perform(get("/api/integration/v1/books"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void tokenSaiAudIssHetHanHoacSaiChuKyDeuLa401CungMotThan() throws Exception {
        TestTokens t = Keys.TOKENS;
        List<String> bad = List.of(
                t.with(TestTokens.ISSUER, null, Duration.ofMinutes(5), TestTokens.claims()),
                t.with(TestTokens.ISSUER, List.of("lms-staff"), Duration.ofMinutes(5), TestTokens.claims()),
                t.with(TestTokens.ISSUER, List.of("lms-storefront"), Duration.ofMinutes(5), TestTokens.claims()),
                t.with(TestTokens.ISSUER, List.of("lms-integration"), Duration.ofMinutes(5),
                        Map.of("svc", "customer-api")),
                t.with("ke-tan-cong", List.of("lms-integration"), Duration.ofMinutes(5), TestTokens.claims()),
                t.with(TestTokens.ISSUER, List.of("lms-integration"), Duration.ofMinutes(-5), TestTokens.claims()),
                new TestTokens().valid());
        String first = null;
        for (String token : bad) {
            String body = mvc.perform(get("/api/integration/v1/books").header("Authorization", bearer(token)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andReturn().getResponse().getContentAsString();
            String code = body.replaceAll("\"timestamp\":\"[^\"]*\"", "");
            if (first == null) first = code;
            assertThat(code).isEqualTo(first);
        }
    }

    @Test
    void tokenIntegrationHopLeNhungChuaCoEndpointDuLieuLa404Json() throws Exception {
        mvc.perform(get("/api/integration/v1/books").header("Authorization", bearer(Keys.TOKENS.valid())))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void duongDanNgoaiDanhSachDongLa403KhongLoTonTai() throws Exception {
        for (String path : List.of("/internal/oauth2/token", "/internal/anything", "/api/auth/login", "/public/t/x",
                "/oauth/token", "/api/integration/v10/x", "/")) {
            mvc.perform(get(path).header("Authorization", bearer(Keys.TOKENS.valid())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mvc.perform(get(path)).andExpect(status().isForbidden());
        }
    }

    @Test
    void khongCoCorsVaCoHeaderBaoMat() throws Exception {
        mvc.perform(post(TOKEN).header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("grant_type", "client_credentials")
                        .header("Authorization", basic()))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    // ------------------------------------------------------------------ /oauth/token

    @Test
    void tokenEndpointCongKhaiVaChuyenTiepFormCungBasic() throws Exception {
        when(oauth.requestToken(any())).thenReturn(TokenResult.success(
                new TokenResponse("jwt-ky", "Bearer", 300, "books:read")));

        mvc.perform(form("grant_type", "client_credentials", "scope", "books:read").header("Authorization", basic()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.access_token").value("jwt-ky"))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(300))
                .andExpect(jsonPath("$.scope").value("books:read"));

        ArgumentCaptor<TokenRequest> captor = ArgumentCaptor.forClass(TokenRequest.class);
        verify(oauth).requestToken(captor.capture());
        assertThat(captor.getValue().basicAuthorization()).isEqualTo(basic());
        assertThat(captor.getValue().grantType()).isEqualTo("client_credentials");
        assertThat(captor.getValue().scope()).isEqualTo("books:read");
        assertThat(captor.getValue().clientId()).isNull();
    }

    @Test
    void thongTinClientTrongThanDuocChuyenTiep() throws Exception {
        when(oauth.requestToken(any())).thenReturn(TokenResult.success(
                new TokenResponse("jwt-ky", "Bearer", 300, "books:read")));

        mvc.perform(form("grant_type", "client_credentials", "client_id", CLIENT_ID, "client_secret", SECRET))
                .andExpect(status().isOk());

        ArgumentCaptor<TokenRequest> captor = ArgumentCaptor.forClass(TokenRequest.class);
        verify(oauth).requestToken(captor.capture());
        assertThat(captor.getValue().basicAuthorization()).isNull();
        assertThat(captor.getValue().clientId()).isEqualTo(CLIENT_ID);
        assertThat(captor.getValue().clientSecret()).isEqualTo(SECRET);
    }

    @Test
    void headerBearerRacOTokenEndpointKhongLamHongYeuCau() throws Exception {
        when(oauth.requestToken(any())).thenReturn(TokenResult.success(
                new TokenResponse("jwt-ky", "Bearer", 300, "books:read")));

        mvc.perform(form("grant_type", "client_credentials", "client_id", CLIENT_ID, "client_secret", SECRET)
                        .header("Authorization", "Bearer het-han-hoac-rac"))
                .andExpect(status().isOk());

        ArgumentCaptor<TokenRequest> captor = ArgumentCaptor.forClass(TokenRequest.class);
        verify(oauth).requestToken(captor.capture());
        assertThat(captor.getValue().basicAuthorization()).isNull();
    }

    @Test
    void loiRfcDuocChuyenDungMaTrangThaiVaWwwAuthenticate() throws Exception {
        record Case(OAuthErrorCode code, int status, boolean www) { }
        for (Case c : List.of(new Case(OAuthErrorCode.INVALID_CLIENT, 401, true),
                new Case(OAuthErrorCode.INVALID_REQUEST, 400, false),
                new Case(OAuthErrorCode.UNSUPPORTED_GRANT_TYPE, 400, false),
                new Case(OAuthErrorCode.INVALID_SCOPE, 400, false))) {
            when(oauth.requestToken(any())).thenReturn(TokenResult.failure(c.code()));

            var result = mvc.perform(form("grant_type", "client_credentials").header("Authorization", basic()))
                    .andExpect(status().is(c.status()))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("Pragma", "no-cache"))
                    .andExpect(jsonPath("$.error").value(c.code().code()))
                    .andExpect(jsonPath("$.error_description").exists());
            if (c.www()) {
                result.andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Basic")));
            } else {
                result.andExpect(header().doesNotExist("WWW-Authenticate"));
            }
        }
    }

    @Test
    void loiHaTangLa503ChungKhongLoChiTiet() throws Exception {
        when(oauth.requestToken(any())).thenThrow(IntegrationApiException.serviceUnavailable());

        String body = mvc.perform(form("grant_type", "client_credentials").header("Authorization", basic()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error").value("temporarily_unavailable"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("identity").doesNotContain("localhost").doesNotContain(SECRET);
    }

    @Test
    void thamSoTrenQueryStringKhongChuyenTiep() throws Exception {
        mvc.perform(post(TOKEN + "?client_secret=" + SECRET).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(header().string("Cache-Control", "no-store"));

        verifyNoInteractions(oauth);
    }

    @Test
    void khongPhaiFormUrlEncodedVaThamSoLapKhongChuyenTiep() throws Exception {
        mvc.perform(post(TOKEN).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        mvc.perform(post(TOKEN)).andExpect(status().isBadRequest());
        mvc.perform(form("grant_type", "client_credentials").param("grant_type", "client_credentials"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));

        verifyNoInteractions(oauth);
    }

    @Test
    void chiPostDuocPhepODuongDanToken() throws Exception {
        // GET không khớp mẫu công khai (chỉ POST): vào nhóm cần token, nên không token là 401; có token thì 405
        mvc.perform(get(TOKEN)).andExpect(status().isUnauthorized());
        mvc.perform(get(TOKEN).header("Authorization", bearer(Keys.TOKENS.valid())))
                .andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(oauth);
    }

    @Test
    void khongLogSecretHayAuthorization(CapturedOutput output) throws Exception {
        when(oauth.requestToken(any())).thenReturn(TokenResult.failure(OAuthErrorCode.INVALID_CLIENT));

        mvc.perform(form("grant_type", "client_credentials").header("Authorization", basic()));
        mvc.perform(form("grant_type", "client_credentials", "client_id", CLIENT_ID, "client_secret", SECRET));

        assertThat(output.getAll()).doesNotContain(SECRET).doesNotContain(
                Base64.getEncoder().encodeToString((CLIENT_ID + ":" + SECRET).getBytes(StandardCharsets.UTF_8)));
    }
}

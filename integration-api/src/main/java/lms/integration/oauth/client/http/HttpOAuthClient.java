package lms.integration.oauth.client.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import lms.integration.oauth.client.OAuthClient;
import lms.integration.oauth.dto.OAuthDtos.TokenRequest;
import lms.integration.oauth.dto.OAuthDtos.TokenResponse;
import lms.integration.oauth.dto.OAuthDtos.UpstreamError;
import lms.integration.web.IntegrationApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Chuyển form tới identity {@code POST /internal/oauth2/token}. Chỉ chuyển Authorization kiểu Basic của client và bốn
 * trường form allowlist (grant_type, scope, client_id, client_secret). Chấp nhận phản hồi 200 (token đủ trường) và 400/401
 * với mã lỗi RFC 6749 trong tập đóng; mọi thứ khác (5xx, 403/404/429, thân lạ, mất kết nối, hết thời gian) thành 503 chung.
 * Không log thân, header Authorization hay secret; chỉ log mã trạng thái và loại lỗi.
 */
public class HttpOAuthClient implements OAuthClient {

    private static final Logger log = LoggerFactory.getLogger(HttpOAuthClient.class);
    static final String TOKEN_URI = "/internal/oauth2/token";
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;

    private final RestClient client;
    private final ObjectMapper mapper;

    public HttpOAuthClient(RestClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public TokenResult requestToken(TokenRequest request) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        add(form, "grant_type", request.grantType());
        add(form, "scope", request.scope());
        add(form, "client_id", request.clientId());
        add(form, "client_secret", request.clientSecret());
        try {
            return client.post().uri(TOKEN_URI).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        if (request.basicAuthorization() != null) {
                            h.set(HttpHeaders.AUTHORIZATION, request.basicAuthorization());
                        }
                    })
                    .body(form)
                    .exchange((req, res) -> {
                        int status = res.getStatusCode().value();
                        byte[] body = res.getBody().readNBytes(MAX_RESPONSE_BYTES);
                        return interpret(status, body);
                    });
        } catch (IntegrationApiException e) {
            throw e;
        } catch (RuntimeException e) {
            // Mất kết nối, hết thời gian, lỗi giải mã: chỉ ghi loại lỗi (không có thân/secret)
            log.warn("Gọi identity token endpoint thất bại: {}", e.getClass().getSimpleName());
            throw IntegrationApiException.serviceUnavailable();
        }
    }

    private TokenResult interpret(int status, byte[] body) {
        TokenResult result = null;
        try {
            if (status == 200) {
                TokenResponse token = mapper.readValue(body, TokenResponse.class);
                if (token.accessToken() != null && !token.accessToken().isBlank()
                        && "Bearer".equalsIgnoreCase(token.tokenType()) && token.expiresIn() > 0) {
                    result = TokenResult.success(new TokenResponse(token.accessToken(), "Bearer", token.expiresIn(),
                            token.scope()));
                }
            } else if (status == 400 || status == 401) {
                UpstreamError upstream = mapper.readValue(body, UpstreamError.class);
                OAuthErrorCode code = OAuthErrorCode.fromCode(upstream.error());
                // 401 chỉ hợp lệ với invalid_client, 400 chỉ với các mã còn lại
                if (code != null && code.status() == status) {
                    result = TokenResult.failure(code);
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            // thân không phải JSON mong đợi: rơi xuống 503
        }
        if (result == null) {
            log.warn("identity token endpoint trả phản hồi không hợp lệ: status={}", status);
            throw IntegrationApiException.serviceUnavailable();
        }
        return result;
    }

    private static void add(MultiValueMap<String, String> form, String name, String value) {
        if (value != null && !value.isEmpty()) {
            form.add(name, value);
        }
    }
}

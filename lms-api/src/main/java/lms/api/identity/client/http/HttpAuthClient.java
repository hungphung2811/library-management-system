package lms.api.identity.client.http;

import lms.api.identity.client.AuthClient;
import lms.api.identity.dto.AuthDtos.ChangePasswordRequest;
import lms.api.identity.dto.AuthDtos.CredentialCreated;
import lms.api.identity.dto.AuthDtos.ExchangeRequest;
import lms.api.identity.dto.AuthDtos.ExternalLoginView;
import lms.api.identity.dto.AuthDtos.LoginRequest;
import lms.api.identity.dto.AuthDtos.ProviderView;
import lms.api.identity.dto.AuthDtos.RefreshRequest;
import lms.api.identity.dto.AuthDtos.RegisterCredentialRequest;
import lms.api.identity.dto.AuthDtos.SwitchTenantRequest;
import lms.api.identity.dto.AuthDtos.TokenView;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/**
 * Gọi API nội bộ /internal/auth/** của identity-service. RestClient truyền vào PHẢI không tự follow redirect
 * (xem IdentityClientConfiguration): start/callback của đăng nhập ngoài trả 302 mà trình duyệt phải nhận nguyên vẹn.
 */
public class HttpAuthClient implements AuthClient {

    static final String EXTERNAL_COOKIE = "lms_ext";

    /** Chỉ các header này của service được chuyển cho trình duyệt. */
    private static final List<String> FORWARDED = List.of(HttpHeaders.LOCATION, HttpHeaders.SET_COOKIE,
            HttpHeaders.CACHE_CONTROL, HttpHeaders.CONTENT_TYPE);

    private final RestClient client;

    public HttpAuthClient(RestClient client) {
        this.client = client;
    }

    @Override
    public TokenView login(String username, String password, String tenantCode) {
        return client.post().uri("/internal/auth/login").body(new LoginRequest(username, password, tenantCode)).retrieve()
                .body(TokenView.class);
    }

    @Override
    public TokenView refresh(String refreshToken) {
        return client.post().uri("/internal/auth/refresh").body(new RefreshRequest(refreshToken)).retrieve()
                .body(TokenView.class);
    }

    @Override
    public void logout(String refreshToken) {
        client.post().uri("/internal/auth/logout").body(new RefreshRequest(refreshToken)).retrieve().toBodilessEntity();
    }

    @Override
    public TokenView switchTenant(SwitchTenantRequest request) {
        return client.post().uri("/internal/auth/switch-tenant").body(request).retrieve().body(TokenView.class);
    }

    @Override
    public void changePassword(ChangePasswordRequest request) {
        client.post().uri("/internal/auth/password").body(request).retrieve().toBodilessEntity();
    }

    @Override
    public CredentialCreated register(RegisterCredentialRequest request) {
        return client.post().uri("/internal/auth/credentials").body(request).retrieve().body(CredentialCreated.class);
    }

    @Override
    public List<ProviderView> externalProviders() {
        return client.get().uri("/internal/auth/external/providers").retrieve()
                .body(new ParameterizedTypeReference<List<ProviderView>>() { });
    }

    @Override
    public ResponseEntity<byte[]> externalStart(String provider, String returnUrl) {
        return raw(client.get().uri("/internal/auth/external/{p}/start?returnUrl={r}", provider, returnUrl));
    }

    @Override
    public ResponseEntity<byte[]> externalCallback(String provider, String code, String state, String error,
                                                   String lmsExtCookie) {
        Map<String, String> query = new LinkedHashMap<>();
        if (notBlank(code)) query.put("code", code);
        if (notBlank(state)) query.put("state", state);
        if (notBlank(error)) query.put("error", error);

        StringBuilder template = new StringBuilder("/internal/auth/external/{p}/callback");
        Map<String, String> vars = new HashMap<>(query);
        vars.put("p", provider);
        String sep = "?";
        for (String key : query.keySet()) {
            template.append(sep).append(key).append("={").append(key).append('}');
            sep = "&";
        }
        RestClient.RequestHeadersSpec<?> spec = client.get().uri(template.toString(), vars);
        if (notBlank(lmsExtCookie)) {
            spec = spec.header(HttpHeaders.COOKIE, EXTERNAL_COOKIE + "=" + lmsExtCookie);
        }
        return raw(spec);
    }

    @Override
    public TokenView externalExchange(String code) {
        return client.post().uri("/internal/auth/external/exchange").body(new ExchangeRequest(code)).retrieve()
                .body(TokenView.class);
    }

    @Override
    public ResponseEntity<byte[]> externalLink(String provider, String returnUrl) {
        return raw(client.get().uri("/internal/auth/me/external/{p}/link?returnUrl={r}", provider, returnUrl));
    }

    @Override
    public List<ExternalLoginView> myExternalLogins() {
        return client.get().uri("/internal/auth/me/external").retrieve()
                .body(new ParameterizedTypeReference<List<ExternalLoginView>>() { });
    }

    @Override
    public void unlinkExternal(String provider) {
        client.delete().uri("/internal/auth/me/external/{p}", provider).retrieve().toBodilessEntity();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * Lấy phản hồi thô: mọi status dưới 500 (kể cả 302) được chuyển nguyên với Location/Set-Cookie/body; 5xx ném
     * lỗi để ApiExceptionHandler đổi thành 502.
     */
    private static ResponseEntity<byte[]> raw(RestClient.RequestHeadersSpec<?> spec) {
        return spec.exchange((request, response) -> {
            byte[] body = response.getBody().readAllBytes();
            if (response.getStatusCode().is5xxServerError()) {
                throw HttpServerErrorException.create(response.getStatusCode(), response.getStatusText(),
                        response.getHeaders(), body, StandardCharsets.UTF_8);
            }
            HttpHeaders out = new HttpHeaders();
            for (String name : FORWARDED) {
                List<String> values = response.getHeaders().get(name);
                if (values != null) out.addAll(name, values);
            }
            return ResponseEntity.status(response.getStatusCode()).headers(out).body(body);
        });
    }
}

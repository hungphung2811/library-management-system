package lms.api.identity.client.http;

import lms.api.identity.client.AuthClient;
import lms.api.identity.dto.AuthDtos.ChangePasswordRequest;
import lms.api.identity.dto.AuthDtos.CredentialCreated;
import lms.api.identity.dto.AuthDtos.ExchangeRequest;
import lms.api.identity.dto.AuthDtos.ExternalLoginView;
import lms.api.identity.dto.AuthDtos.LoginRequest;
import lms.api.identity.dto.AuthDtos.PasswordResetConfirmRequest;
import lms.api.identity.dto.AuthDtos.PasswordResetRequest;
import lms.api.identity.dto.AuthDtos.ProviderView;
import lms.api.identity.dto.AuthDtos.RefreshRequest;
import lms.api.identity.dto.AuthDtos.RegisterCredentialRequest;
import lms.api.identity.dto.AuthDtos.RevokeSessionsRequest;
import lms.api.identity.dto.AuthDtos.SessionView;
import lms.api.identity.dto.AuthDtos.SwitchTenantRequest;
import lms.api.identity.dto.AuthDtos.TokenView;
import lms.api.identity.dto.UserDtos.RevokedSessionsView;
import lms.api.support.ClientInfo;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Supplier;
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

    static final String CLIENT_IP_HEADER = "X-Client-Ip";
    static final String CLIENT_UA_HEADER = "X-Client-User-Agent";

    private final RestClient client;
    private final Supplier<ClientInfo> clientInfo;

    public HttpAuthClient(RestClient client) {
        this(client, () -> ClientInfo.NONE);
    }

    /** clientInfo: IP/user-agent của người gọi, chỉ gắn vào các lời gọi phát token (ADR-0017). */
    public HttpAuthClient(RestClient client, Supplier<ClientInfo> clientInfo) {
        this.client = client;
        this.clientInfo = clientInfo;
    }

    /** Gắn X-Client-Ip/X-Client-User-Agent (nếu có) vào lời gọi phát token. */
    private RestClient.RequestBodySpec issuing(String uri) {
        RestClient.RequestBodySpec spec = client.post().uri(uri);
        ClientInfo info = clientInfo.get();
        if (info != null) {
            if (info.ip() != null) spec = spec.header(CLIENT_IP_HEADER, info.ip());
            if (info.userAgent() != null) spec = spec.header(CLIENT_UA_HEADER, info.userAgent());
        }
        return spec;
    }

    @Override
    public TokenView login(String username, String password, String tenantCode) {
        return issuing("/internal/auth/login").body(new LoginRequest(username, password, tenantCode)).retrieve()
                .body(TokenView.class);
    }

    @Override
    public TokenView refresh(String refreshToken) {
        return issuing("/internal/auth/refresh").body(new RefreshRequest(refreshToken)).retrieve()
                .body(TokenView.class);
    }

    @Override
    public List<SessionView> mySessions() {
        return client.get().uri("/internal/auth/sessions").retrieve()
                .body(new ParameterizedTypeReference<List<SessionView>>() { });
    }

    @Override
    public void revokeMySession(UUID sessionId) {
        client.delete().uri("/internal/auth/sessions/{id}", sessionId).retrieve().toBodilessEntity();
    }

    @Override
    public RevokedSessionsView revokeMySessions(boolean keepCurrent) {
        return client.post().uri("/internal/auth/sessions/revocation").body(new RevokeSessionsRequest(keepCurrent))
                .retrieve().body(RevokedSessionsView.class);
    }

    @Override
    public void logout(String refreshToken) {
        client.post().uri("/internal/auth/logout").body(new RefreshRequest(refreshToken)).retrieve().toBodilessEntity();
    }

    @Override
    public TokenView switchTenant(SwitchTenantRequest request) {
        return issuing("/internal/auth/switch-tenant").body(request).retrieve().body(TokenView.class);
    }

    @Override
    public void changePassword(ChangePasswordRequest request) {
        client.post().uri("/internal/auth/password").body(request).retrieve().toBodilessEntity();
    }

    @Override
    public void requestPasswordReset(String username) {
        client.post().uri("/internal/auth/password-reset-requests")
                .body(new PasswordResetRequest(username)).retrieve().toBodilessEntity();
    }

    @Override
    public void confirmPasswordReset(String token, String newPassword) {
        client.post().uri("/internal/auth/password-reset-confirmations")
                .body(new PasswordResetConfirmRequest(token, newPassword)).retrieve().toBodilessEntity();
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
        return issuing("/internal/auth/external/exchange").body(new ExchangeRequest(code)).retrieve()
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

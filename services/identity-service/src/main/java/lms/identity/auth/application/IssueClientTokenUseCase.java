package lms.identity.auth.application;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lms.identity.auth.application.ClientAuthenticationPort.ClientPrincipal;
import lms.identity.auth.application.ClientTokenIssuerPort.ClientClaims;
import lms.identity.auth.domain.ClientTokenException;
import lms.identity.auth.domain.ClientTokenException.Error;

/**
 * Cấp token tích hợp theo OAuth2 client_credentials (RFC 6749 mục 4.4). Thứ tự: thiếu thông tin client hoặc sai
 * grant_type (không chạm DB) -> xác thực client (mọi lý do thất bại cùng {@code invalid_client}) -> scope. Scope yêu cầu
 * phải thuộc scope ĐÃ CẤP cho Application, nếu không {@code invalid_scope}; token chỉ mang phần thuộc module tenant đang
 * bật (loại phần còn lại, rỗng thì {@code invalid_scope}). Không có tham số scope thì cấp mọi scope đã cấp còn khả dụng.
 */
public class IssueClientTokenUseCase {

    public static final String GRANT_TYPE = "client_credentials";

    private final ClientAuthenticationPort clients;
    private final ClientTokenIssuerPort issuer;
    private final Duration ttl;

    public IssueClientTokenUseCase(ClientAuthenticationPort clients, ClientTokenIssuerPort issuer, Duration ttl) {
        this.clients = clients;
        this.issuer = issuer;
        this.ttl = ttl;
    }

    /** grantType/clientId/clientSecret/scope là giá trị thô từ yêu cầu (có thể null). */
    public IssuedClientToken execute(String grantType, String clientId, String clientSecret, String scope) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isEmpty()) {
            throw new ClientTokenException(Error.INVALID_CLIENT);
        }
        if (grantType == null || grantType.isBlank()) {
            throw new ClientTokenException(Error.INVALID_REQUEST);
        }
        if (!GRANT_TYPE.equals(grantType)) {
            throw new ClientTokenException(Error.UNSUPPORTED_GRANT_TYPE);
        }
        ClientPrincipal client = clients.authenticate(clientId, clientSecret)
                .orElseThrow(() -> new ClientTokenException(Error.INVALID_CLIENT));

        List<String> scopes = resolveScopes(client, scope);
        TokenIssuerPort.IssuedToken token = issuer.issueClient(
                new ClientClaims(client.applicationId(), client.tenantId(), scopes, client.modules(), ttl));
        return new IssuedClientToken(token.value(), token.expiresInSeconds(), String.join(" ", scopes));
    }

    private static List<String> resolveScopes(ClientPrincipal client, String raw) {
        List<String> result;
        if (raw == null || raw.isBlank()) {
            result = client.availableScopes();
        } else {
            Set<String> requested = new LinkedHashSet<>(List.of(raw.trim().split("\\s+")));
            if (!client.grantedScopes().containsAll(requested)) {
                throw new ClientTokenException(Error.INVALID_SCOPE);
            }
            result = client.availableScopes().stream().filter(requested::contains).toList();
        }
        if (result.isEmpty()) {
            throw new ClientTokenException(Error.INVALID_SCOPE);
        }
        return result;
    }

    /** toString không in access token. */
    public record IssuedClientToken(String accessToken, long expiresIn, String scope) {
        @Override
        public String toString() {
            return "IssuedClientToken[expiresIn=" + expiresIn + ", scope=" + scope + "]";
        }
    }
}

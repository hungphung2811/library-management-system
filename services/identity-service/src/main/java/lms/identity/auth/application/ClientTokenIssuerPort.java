package lms.identity.auth.application;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Phát token tích hợp (OAuth2 client_credentials): aud lms-integration, sub = client_id = id Application, tenantId,
 * scope, modules, jti. KHÔNG có admin, platformAdmin, roles, permissions, svc, sid.
 */
public interface ClientTokenIssuerPort {

    TokenIssuerPort.IssuedToken issueClient(ClientClaims claims);

    record ClientClaims(UUID applicationId, UUID tenantId, List<String> scopes, List<String> modules, Duration ttl) {
        public ClientClaims {
            scopes = List.copyOf(scopes);
            modules = List.copyOf(modules);
        }
    }
}

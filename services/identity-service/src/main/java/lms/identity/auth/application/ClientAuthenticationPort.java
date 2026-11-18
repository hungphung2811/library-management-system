package lms.identity.auth.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Xác thực client của token endpoint OAuth2 (do context apiclient đảm nhiệm). Rỗng cho MỌI lý do thất bại (không phân
 * biệt). grantedScopes: scope đã cấp cho Application; availableScopes: phần còn dùng được theo module tenant đang bật.
 */
public interface ClientAuthenticationPort {

    Optional<ClientPrincipal> authenticate(String clientId, String clientSecret);

    record ClientPrincipal(UUID applicationId, UUID tenantId, List<String> grantedScopes,
                           List<String> availableScopes, List<String> modules) {
        public ClientPrincipal {
            grantedScopes = List.copyOf(grantedScopes);
            availableScopes = List.copyOf(availableScopes);
            modules = List.copyOf(modules);
        }
    }
}

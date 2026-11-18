package lms.security;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Ngữ cảnh người gọi dựng theo yêu cầu từ {@code SecurityContext} của request hiện tại (không có ThreadLocal riêng).
 * Chỉ để ghi log/audit và phân biệt loại xác thực: nguồn tenant duy nhất cho truy vấn vẫn là {@code TenantContext}.
 *
 * @param tenantId      claim {@code tenantId} của token (null với platform admin); không dùng để truy vấn
 * @param applicationId claim {@code application_id} (chỉ token integration, có thể null)
 * @param subject       claim {@code sub}
 * @param scopes        các scope trong claim {@code scope} (rỗng nếu không có)
 */
public record CallerIdentity(AuthType type, String subject, UUID tenantId, UUID applicationId, Set<String> scopes) {

    /** Loại xác thực suy từ claim đã ký, không từ header/body của client. */
    public enum AuthType { STAFF, STOREFRONT, SERVICE, INTEGRATION }

    public CallerIdentity {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    /** Người gọi hiện tại; rỗng nếu chưa xác thực bằng JWT. */
    public static Optional<CallerIdentity> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth) || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return Optional.of(from(jwtAuth.getToken()));
    }

    public static CallerIdentity from(Jwt jwt) {
        return new CallerIdentity(typeOf(jwt), jwt.getSubject(), uuid(jwt.getClaimAsString(JwtClaims.TENANT_ID)),
                uuid(jwt.getClaimAsString(JwtClaims.APPLICATION_ID)), scopesOf(jwt));
    }

    /** True nếu {@code aud} của token chứa {@code audience}. */
    public static boolean hasAudience(Jwt jwt, String audience) {
        Object aud = jwt.getClaim(JwtClaims.AUDIENCE);
        if (aud instanceof Collection<?> values) {
            return values.stream().anyMatch(v -> audience.equals(String.valueOf(v)));
        }
        return aud != null && audience.equals(aud.toString());
    }

    private static AuthType typeOf(Jwt jwt) {
        if (hasAudience(jwt, JwtClaims.AUDIENCE_INTEGRATION)) return AuthType.INTEGRATION;
        if (jwt.hasClaim(JwtClaims.SERVICE)) return AuthType.SERVICE;
        if (hasAudience(jwt, JwtClaims.AUDIENCE_STOREFRONT)) return AuthType.STOREFRONT;
        return AuthType.STAFF;
    }

    private static Set<String> scopesOf(Jwt jwt) {
        Object raw = jwt.getClaim(JwtClaims.SCOPE);
        if (raw instanceof String text) {
            return java.util.Arrays.stream(text.trim().split("\s+")).filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
        }
        return Set.of();
    }

    private static UUID uuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

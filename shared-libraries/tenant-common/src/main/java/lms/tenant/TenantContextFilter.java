package lms.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import lms.security.JwtClaims;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Đặt {@link TenantContext} từ claim {@code tenantId} của JWT đã xác thực và xóa khi xong request. Phải chạy sau
 * bước xác thực JWT. Filter này không từ chối request: việc 403 TENANT_REQUIRED do PermissionInterceptor đảm nhiệm.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            tenantFromAuthentication().ifPresent(TenantContext::set);
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static Optional<UUID> tenantFromAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt)) return Optional.empty();
        String claim = jwt.getToken().getClaimAsString(JwtClaims.TENANT_ID);
        if (claim == null || claim.isBlank()) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(claim));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}

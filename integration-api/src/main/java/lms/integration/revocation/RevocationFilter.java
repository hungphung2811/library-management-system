package lms.integration.revocation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lms.integration.config.JsonErrorWriter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Từ chối token integration đã bị thu hồi bằng {@code 401 UNAUTHORIZED} (thân giống token không hợp lệ, không nói lý do).
 * Chạy SAU khi token đã xác thực và TRƯỚC giới hạn tốc độ; chỉ áp cho {@code /api/integration/v1/**} trừ token endpoint.
 */
public class RevocationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/api/integration/v1/";

    private final TokenRevocationChecker checker;
    private final JsonErrorWriter writer;

    public RevocationFilter(TokenRevocationChecker checker, JsonErrorWriter writer) {
        this.checker = checker;
        this.writer = writer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith(PREFIX) || uri.startsWith(PREFIX + "oauth/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt && checker.isRevoked(jwt.getToken())) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            writer.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Token không hợp lệ hoặc thiếu");
            return;
        }
        chain.doFilter(request, response);
    }
}

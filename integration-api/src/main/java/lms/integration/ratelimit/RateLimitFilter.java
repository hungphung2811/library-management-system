package lms.integration.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import lms.integration.config.JsonErrorWriter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Giới hạn tốc độ theo application, chạy SAU khi token đã được xác thực (tenant/application lấy từ claim). Chỉ áp cho
 * {@code /api/integration/v1/**} trừ token endpoint (đã có giới hạn IP ở nginx và không có token). Vượt hạn mức thì
 * {@code 429 RATE_LIMITED} + {@code Retry-After}; mọi phản hồi được đếm có {@code X-RateLimit-Limit} và
 * {@code X-RateLimit-Remaining}. Fail-open ở {@link ApiRateLimiter}.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    static final String PREFIX = "/api/integration/v1/";

    private final ApiRateLimiter limiter;
    private final JsonErrorWriter writer;

    public RateLimitFilter(ApiRateLimiter limiter, JsonErrorWriter writer) {
        this.limiter = limiter;
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
        ApiCaller caller = authentication instanceof JwtAuthenticationToken jwt ? ApiCaller.from(jwt.getToken()) : null;
        Optional<ApiRateLimiter.Decision> decision = caller == null ? Optional.empty()
                : limiter.check(caller, group(request.getRequestURI()));
        if (decision.isPresent()) {
            ApiRateLimiter.Decision d = decision.get();
            response.setHeader("X-RateLimit-Limit", String.valueOf(d.limit()));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(d.remaining()));
            if (!d.allowed()) {
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(d.retryAfterSeconds()));
                writer.write(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "Vượt quá hạn mức yêu cầu, vui lòng thử lại sau");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** Nhóm endpoint từ đường dẫn (tập đóng, khóa Redis không thể bị chèn giá trị tùy ý). */
    static String group(String uri) {
        String rest = uri.startsWith(PREFIX) ? uri.substring(PREFIX.length()) : "";
        int slash = rest.indexOf('/');
        String first = slash < 0 ? rest : rest.substring(0, slash);
        return switch (first) {
            case "books", "inventory", "orders" -> first;
            default -> "other";
        };
    }
}

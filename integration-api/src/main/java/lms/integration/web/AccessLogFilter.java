package lms.integration.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Log truy cập có cấu trúc, một dòng mỗi request tới {@code /api/integration/v1/**}: correlationId, jti, tenantId,
 * applicationId, method, MẪU route (vd {@code /api/integration/v1/books/{id}}, không có id thật hay query), status,
 * thời gian ms. Tuyệt đối KHÔNG log thân, header Authorization, secret hay token; các giá trị claim được lọc ký tự lạ.
 * Đặt trước {@code BearerTokenAuthenticationFilter} nên vẫn ghi cả request bị 401 (khi đó claim là {@code -}).
 */
public class AccessLogFilter extends OncePerRequestFilter {

    static final String LOGGER_NAME = "lms.integration.access";
    private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);
    private static final String PREFIX = "/api/integration/v1/";
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._:/{}\\-]");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long millis = (System.nanoTime() - start) / 1_000_000;
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String jti = "-";
            String tenant = "-";
            String application = "-";
            if (authentication instanceof JwtAuthenticationToken jwt) {
                jti = clean(jwt.getToken().getId());
                tenant = clean(jwt.getToken().getClaimAsString("tenantId"));
                application = clean(jwt.getToken().getClaimAsString("application_id"));
            }
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String route = pattern == null ? "unmatched" : clean(pattern.toString());
            log.info("integration-access correlationId={} jti={} tenantId={} applicationId={} method={} route={} "
                            + "status={} durationMs={}", clean(MDC.get("correlationId")), jti, tenant, application,
                    clean(request.getMethod()), route, response.getStatus(), millis);
        }
    }

    private static String clean(String value) {
        if (value == null || value.isEmpty()) return "-";
        String s = value.length() > 128 ? value.substring(0, 128) : value;
        return UNSAFE.matcher(s).replaceAll("_");
    }
}

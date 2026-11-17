package lms.customer.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Từ chối token dịch vụ (claim {@code svc}) lọt ra ngoài: token này chỉ để customer-api gọi nội bộ (ADR-0011 2.4),
 * không được dùng làm phiên của khách. Không phụ thuộc security-common nên dùng hằng chuỗi.
 */
public class ServiceTokenRejectionFilter extends OncePerRequestFilter {

    static final String SERVICE_CLAIM = "svc";

    private final JsonErrorWriter writer;

    public ServiceTokenRejectionFilter(JsonErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt && jwt.getToken().hasClaim(SERVICE_CLAIM)) {
            SecurityContextHolder.clearContext();
            writer.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Phiên đăng nhập không hợp lệ");
            return;
        }
        chain.doFilter(request, response);
    }
}

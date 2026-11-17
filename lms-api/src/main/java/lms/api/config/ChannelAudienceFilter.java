package lms.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ranh giới kênh token ở lms-api (customer-api-data-authorization A1, 5.3 phương án C): token kênh storefront (claim
 * {@code aud} chứa {@code lms-storefront}, phát cho website khách qua customer-api) KHÔNG dùng được ở lms-api, trả 401
 * như token không hợp lệ (cùng cơ chế với token hết hạn, không lộ lý do). Chạy sau BearerTokenAuthenticationFilter.
 *
 * <p>Tương thích ngược: token không có {@code aud} (mọi token phát hành trước thay đổi này) vẫn hợp lệ. Khi hết giai
 * đoạn chuyển tiếp bật {@code requireStaffAudience} (cấu hình {@code lms.api.security.require-staff-audience}, cùng
 * lúc identity bật {@code lms.identity.token.staff-audience}) để chỉ nhận token có {@code aud = lms-staff}.
 */
public class ChannelAudienceFilter extends OncePerRequestFilter {

    /** Trùng AUDIENCE_STOREFRONT/AUDIENCE_STAFF của security-common (lms-api không phụ thuộc thư viện đó). */
    static final String AUDIENCE_STOREFRONT = "lms-storefront";
    static final String AUDIENCE_STAFF = "lms-staff";

    private final boolean requireStaffAudience;
    private final BearerTokenAuthenticationEntryPoint entryPoint = new BearerTokenAuthenticationEntryPoint();

    public ChannelAudienceFilter(boolean requireStaffAudience) {
        this.requireStaffAudience = requireStaffAudience;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt && !acceptable(jwt.getToken().getAudience())) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(request, response, new InvalidBearerTokenException("Token không hợp lệ cho kênh này"));
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean acceptable(List<String> audience) {
        List<String> aud = audience == null ? List.of() : audience;
        if (aud.contains(AUDIENCE_STOREFRONT)) return false;
        return !requireStaffAudience || aud.contains(AUDIENCE_STAFF);
    }
}

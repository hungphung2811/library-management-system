package lms.customer.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lms.customer.storefront.StorefrontAccessCache;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CORS động cho {@code /public/**} (ADR-0013 2.1, 2.2). Chạy TRƯỚC mọi filter có thể trả lỗi (đăng ký servlet với thứ
 * tự rất cao, trước cả bộ lọc kích thước thân và chuỗi Spring Security) nên 401/403/404/413/503 cũng mang header CORS khi
 * origin được phép, và preflight {@code OPTIONS} luôn được trả lời 204 tại đây, không bao giờ tới controller.
 *
 * <p>Quyết theo {@code Origin} và {@code {code}} của đường dẫn {@code /public/t/{code}/**}:
 * origin nền tảng, origin dev, hoặc {@code allowedOrigins} của tenant (từ {@link StorefrontAccessCache}). Đường dẫn
 * không có mã ({@code /public/site/**}, {@code /public/auth/refresh|logout}) chỉ nhận origin nền tảng và dev.
 * Origin không được phép chỉ bị bỏ header CORS: không mã lỗi riêng, không đổi thân phản hồi, không lộ tenant.
 *
 * <p>Bearer token trong header, không cookie: KHÔNG bao giờ gửi {@code Access-Control-Allow-Credentials}. So khớp chính
 * xác (sau khi hạ chữ thường) với tập đã chuẩn hóa, từ chối {@code null}, nhiều header, dấu phẩy, quá dài.
 *
 * <p>Public Site Key (SaaS API Authentication P2): {@code /api/public/**} quyết theo header {@code X-Site-Key} thay cho
 * {code}: CHỈ origin của Application (allowedOrigins phân giải từ site key, bộ đệm tối đa 60 giây), KHÔNG cộng origin nền
 * tảng/dev. Riêng preflight {@code OPTIONS}: trình duyệt không gửi header tùy chỉnh (X-Site-Key) trong preflight nên không
 * thể biết Application; preflight chỉ trả Allow-Origin phản chiếu origin hợp lệ về cú pháp (không mang dữ liệu, không
 * thực thi gì), còn quyết định thật nằm ở phản hồi của request chính (không Allow-Origin thì trình duyệt chặn đọc).
 * Origin không phải xác thực.
 */
public class PublicCorsFilter extends OncePerRequestFilter {

    static final int MAX_ORIGIN_LENGTH = 300;
    private static final Pattern CODE_PATH = Pattern.compile("^/public/t/([^/]+)(?:/.*)?$");
    static final String SITE_KEY_HEADER = "X-Site-Key";
    private static final String SITE_KEY_PREFIX = "/api/public/";
    private static final Logger log = LoggerFactory.getLogger(PublicCorsFilter.class);

    private final PublicCorsProperties properties;
    private final StorefrontAccessCache storefronts;
    private final Set<String> alwaysAllowed;
    /** Giới hạn tần suất log REPORT: một dòng mỗi cặp (mã, origin) mỗi 60 giây, tối đa 1000 cặp. */
    private final Cache<String, Boolean> reported = Caffeine.newBuilder().maximumSize(1_000)
            .expireAfterWrite(Duration.ofSeconds(60)).build();

    public PublicCorsFilter(PublicCorsProperties properties, StorefrontAccessCache storefronts) {
        this.properties = properties;
        this.storefronts = storefronts;
        Set<String> all = new HashSet<>(properties.platformOrigins());
        all.addAll(properties.devOrigins());
        this.alwaysAllowed = Collections.unmodifiableSet(all);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !(uri.startsWith("/public/") || uri.startsWith(SITE_KEY_PREFIX));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
        boolean preflight = HttpMethod.OPTIONS.matches(request.getMethod());
        boolean siteKeyPath = request.getRequestURI().startsWith(SITE_KEY_PREFIX);
        if (siteKeyPath) {
            response.addHeader(HttpHeaders.VARY, SITE_KEY_HEADER);
        }
        String origin = siteKeyPath ? siteKeyOrigin(request, preflight) : allowedOrigin(request);
        if (origin != null) {
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            if (preflight) {
                response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS");
                response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, siteKeyPath
                        ? "Authorization, Content-Type, X-Site-Key, Idempotency-Key, X-Correlation-Id"
                        : "Authorization, Content-Type, X-Correlation-Id");
                response.setHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE, Long.toString(properties.maxAge().toSeconds()));
            } else {
                response.setHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "X-Correlation-Id");
            }
        }
        if (preflight) {
            // Một dạng duy nhất cho mọi trường hợp (được phép hay không, mã tồn tại hay không): 204.
            response.setStatus(HttpServletResponse.SC_NO_CONTENT);
            response.setContentLength(0);
            return;
        }
        chain.doFilter(request, response);
    }

    /** Origin hợp lệ về cú pháp (một header, không rỗng/null/dấu phẩy/quá dài) hoặc {@code null}. */
    private static String syntacticOrigin(HttpServletRequest request) {
        List<String> values = Collections.list(request.getHeaders(HttpHeaders.ORIGIN));
        if (values.isEmpty()) {
            return null;
        }
        String origin = values.get(0);
        if (values.size() > 1 || origin == null || origin.isBlank() || origin.length() > MAX_ORIGIN_LENGTH
                || origin.indexOf(',') >= 0 || "null".equalsIgnoreCase(origin.trim())) {
            return null;
        }
        return origin;
    }

    /**
     * Origin được phép ở {@code /api/public/**} (quyết theo X-Site-Key). Preflight: phản chiếu origin hợp lệ về cú pháp
     * (xem mô tả lớp). Request chính: chỉ origin nằm trong allowedOrigins của Application; không có/không hợp lệ site
     * key hoặc origin lạ thì không có header CORS. Không bao giờ log site key.
     */
    private String siteKeyOrigin(HttpServletRequest request, boolean preflight) {
        String origin = syntacticOrigin(request);
        if (origin == null || preflight || properties.mode() == PublicCorsMode.OFF) {
            return origin;
        }
        String lower = origin.toLowerCase(Locale.ROOT);
        Optional<StorefrontAccess> access = storefronts.tryResolveBySiteKey(request.getHeader(SITE_KEY_HEADER));
        if (access.isPresent() && access.get().allowsOrigin(lower)) {
            return origin;
        }
        if (properties.mode() == PublicCorsMode.REPORT) {
            String key = "sk|" + lower;
            if (reported.asMap().putIfAbsent(key, Boolean.TRUE) == null) {
                log.info("cors-would-reject site-key origin={}", safe(lower));
            }
            return origin;
        }
        return null;
    }

    /** @return origin nguyên văn để đặt vào Allow-Origin, hoặc {@code null} nếu không được phép (hoặc không có Origin). */
    private String allowedOrigin(HttpServletRequest request) {
        String origin = syntacticOrigin(request);
        if (origin == null) {
            return null;
        }
        String lower = origin.toLowerCase(Locale.ROOT);
        if (properties.mode() == PublicCorsMode.OFF) {
            return origin;
        }
        Optional<String> code = codeOf(request.getRequestURI());
        boolean allowed = alwaysAllowed.contains(lower) || (code.isPresent() && tenantAllows(code.get(), lower));
        if (allowed) {
            return origin;
        }
        if (properties.mode() == PublicCorsMode.REPORT) {
            String key = code.orElse("-") + "|" + lower;
            if (reported.asMap().putIfAbsent(key, Boolean.TRUE) == null) {
                log.info("cors-would-reject code={} origin={}", code.map(PublicCorsFilter::safe).orElse("-"), safe(lower));
            }
            return origin;
        }
        return null;
    }

    private boolean tenantAllows(String code, String lowerOrigin) {
        Optional<StorefrontAccess> access = storefronts.tryResolve(code);
        return access.isPresent() && access.get().allowsOrigin(lowerOrigin);
    }

    /** Mã tenant từ đường dẫn {@code /public/t/{code}/...}, đã giải mã %XX và bỏ tham số đường dẫn ({@code ;x=1}). */
    static Optional<String> codeOf(String uri) {
        if (uri == null) {
            return Optional.empty();
        }
        Matcher m = CODE_PATH.matcher(uri);
        if (!m.matches()) {
            return Optional.empty();
        }
        String segment = m.group(1);
        int semicolon = segment.indexOf(';');
        if (semicolon >= 0) {
            segment = segment.substring(0, semicolon);
        }
        try {
            return Optional.of(URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** Giá trị đưa vào log: cắt ngắn, bỏ ký tự điều khiển (chống chèn dòng log). */
    private static String safe(String value) {
        String cleaned = value.replaceAll("\\p{Cntrl}", "?");
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }
}

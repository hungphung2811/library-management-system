package lms.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/** Nhận hoặc sinh X-Correlation-Id, đưa vào MDC (key "correlationId") và trả lại trong response. */
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /** Giá trị client gửi chỉ được chấp nhận khi khớp mẫu an toàn này (UUID và hex 32 ký tự của nginx đều khớp). */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** Header hợp lệ (đúng một giá trị, khớp mẫu an toàn) thì giữ nguyên; ngược lại sinh mã mới, không phản chiếu giá trị rác. */
    private static String resolveId(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(HEADER);
        if (values != null && values.hasMoreElements()) {
            String candidate = values.nextElement();
            if (!values.hasMoreElements() && candidate != null && SAFE_ID.matcher(candidate).matches()) {
                return candidate;
            }
        }
        return UUID.randomUUID().toString();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = resolveId(request);
        MDC.put(MDC_KEY, id);
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}

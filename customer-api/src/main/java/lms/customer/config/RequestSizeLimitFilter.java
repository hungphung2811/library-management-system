package lms.customer.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Giới hạn kích thước thân request ở tầng ứng dụng cho /public/** (nginx đã chặn 16k, đây là lớp thứ hai khi gọi thẳng
 * vào customer-api): Content-Length vượt mức thì 413 ngay, trước khi vào Spring Security/Jackson. Thân gửi kiểu chunked
 * (không có Content-Length) bị chặn ở Jackson bằng StreamReadConstraints.maxDocumentLength cùng mức
 * ({@link JacksonLimitsConfiguration}).
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    public static final long MAX_BODY_BYTES = 16 * 1024;

    private final JsonErrorWriter writer;

    public RequestSizeLimitFilter(JsonErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith("/public/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            // Không đọc thân; đóng kết nối để Tomcat không phải nuốt phần thân còn lại.
            response.setHeader("Connection", "close");
            writer.write(response, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Dữ liệu gửi lên quá lớn");
            return;
        }
        chain.doFilter(request, response);
    }
}

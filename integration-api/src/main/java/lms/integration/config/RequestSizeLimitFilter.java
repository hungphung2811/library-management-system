package lms.integration.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Giới hạn kích thước thân request ở tầng ứng dụng (nginx đã chặn, đây là lớp thứ hai khi gọi thẳng vào integration-api):
 * Content-Length vượt mức thì 413 ngay, trước Spring Security. Token endpoint chỉ nhận form 4 trường nên mức nhỏ
 * (4 KB); phần còn lại 64 KB (design 4.4.1). Thân chunked (không có Content-Length) bị chặn ở Tomcat bằng
 * {@code server.tomcat.max-http-form-post-size}.
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    public static final long MAX_TOKEN_BODY_BYTES = 4 * 1024;
    public static final long MAX_BODY_BYTES = 64 * 1024;

    private final JsonErrorWriter writer;

    public RequestSizeLimitFilter(JsonErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long limit = SecurityConfiguration.TOKEN_PATH.equals(request.getRequestURI())
                ? MAX_TOKEN_BODY_BYTES : MAX_BODY_BYTES;
        if (request.getContentLengthLong() > limit) {
            // Không đọc thân; đóng kết nối để Tomcat không phải nuốt phần thân còn lại.
            response.setHeader("Connection", "close");
            writer.write(response, HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Dữ liệu gửi lên quá lớn");
            return;
        }
        chain.doFilter(request, response);
    }
}

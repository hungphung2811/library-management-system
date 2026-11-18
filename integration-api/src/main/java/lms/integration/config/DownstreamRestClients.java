package lms.integration.config;

import java.net.http.HttpClient;
import java.util.function.Supplier;
import lms.integration.web.IntegrationApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Dựng RestClient gọi catalog/sales và chuẩn hóa lỗi hạ nguồn thành tập mã công khai nhỏ (D2): 400/422 thành
 * {@code 400 INVALID_REQUEST}, 401 thành {@code 401 UNAUTHORIZED}, 403 thành {@code 403 FORBIDDEN} (KHÔNG chuyển thông
 * điệp của service vì có thể chứa tên khóa nội bộ hay tên module), 404 thành {@code 404 NOT_FOUND}; mọi thứ khác
 * (5xx, 429, mất kết nối, hết thời gian, thân lạ) thành {@code 503 SERVICE_UNAVAILABLE} chung. Không bao giờ chuyển thân
 * lỗi của service ra ngoài.
 */
public final class DownstreamRestClients {

    private static final Logger log = LoggerFactory.getLogger(DownstreamRestClients.class);

    private DownstreamRestClients() {
    }

    /** RestClient thật: JDK HttpClient (HTTP/1.1, không theo redirect), timeout hữu hạn, chuyển token + correlation id. */
    public static RestClient create(String baseUrl, ServicesProperties properties) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        return customize(RestClient.builder().baseUrl(baseUrl).requestFactory(factory)).build();
    }

    /** Gắn interceptor và bộ xử lý trạng thái lỗi (test dùng riêng với MockRestServiceServer). */
    public static RestClient.Builder customize(RestClient.Builder builder) {
        return builder.requestInterceptor(new CorrelationIdInterceptor())
                .requestInterceptor(new BearerForwardingInterceptor())
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw translate(response.getStatusCode().value());
                });
    }

    /** Chạy một lời gọi hạ nguồn; lỗi hạ tầng (không phải {@link IntegrationApiException}) thành 503 chung. */
    public static <T> T call(Supplier<T> call) {
        try {
            return call.get();
        } catch (IntegrationApiException e) {
            throw e;
        } catch (RestClientException | IllegalArgumentException | IllegalStateException e) {
            log.warn("Gọi service hạ nguồn thất bại: {}", e.getClass().getSimpleName());
            throw IntegrationApiException.serviceUnavailable();
        }
    }

    static IntegrationApiException translate(int status) {
        return switch (status) {
            case 400, 422 -> new IntegrationApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "Yêu cầu không hợp lệ");
            case 401 -> new IntegrationApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                    "Token không hợp lệ hoặc thiếu");
            case 403 -> new IntegrationApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Token không có quyền truy cập tài nguyên này");
            case 404 -> new IntegrationApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Không tìm thấy tài nguyên");
            default -> {
                log.warn("Service hạ nguồn trả trạng thái {}", status);
                yield IntegrationApiException.serviceUnavailable();
            }
        };
    }
}

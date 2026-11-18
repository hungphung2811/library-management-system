package lms.customer.author.client.http;

import java.util.Set;
import java.util.function.Supplier;
import lms.customer.web.DownstreamErrorCode;
import lms.customer.web.PublicApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClientResponseException;

/**
 * Cùng quy tắc với {@code HttpCatalogClient}: 401/403 khi gọi bằng token dịch vụ là lỗi cấu hình/hạ tầng nên thành 503
 * chung; MODULE_NOT_ENABLED/TENANT_REQUIRED giữ nguyên để PublicExceptionHandler đổi thành 404 SHOP_NOT_AVAILABLE.
 */
final class AuthorDownstream {

    private static final Set<String> SHOP_UNAVAILABLE_CODES = Set.of("MODULE_NOT_ENABLED", "TENANT_REQUIRED");
    private static final Logger log = LoggerFactory.getLogger(AuthorDownstream.class);

    private AuthorDownstream() {
    }

    static <T> T call(String target, Supplier<T> request) {
        try {
            T body = request.get();
            if (body == null) {
                throw PublicApiException.serviceUnavailable();
            }
            return body;
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == HttpStatus.UNAUTHORIZED.value() || (status == HttpStatus.FORBIDDEN.value()
                    && !DownstreamErrorCode.isOneOf(SHOP_UNAVAILABLE_CODES, DownstreamErrorCode.of(ex)))) {
                log.warn("{} từ chối token dịch vụ storefront, status={}", target, status);
                throw PublicApiException.serviceUnavailable();
            }
            throw ex;
        }
    }
}

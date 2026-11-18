package lms.customer.storefront.client.http;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import lms.customer.storefront.client.StorefrontAccessClient;
import lms.customer.storefront.dto.StorefrontDtos.StorefrontAccess;
import lms.customer.web.DownstreamErrorCode;
import lms.customer.web.PublicApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * POST /internal/storefront/access-tokens của identity, xác thực bằng header X-Storefront-Client-Secret.
 * Đã đối chiếu với StorefrontAccessController (B-C2): đường dẫn, header, body {tenantCode}, phản hồi
 * {tenantId, code, name, accessToken, expiresIn}, 404 SHOP_NOT_AVAILABLE, 401 UNAUTHORIZED khi bí mật sai.
 */
public class HttpStorefrontAccessClient implements StorefrontAccessClient {

    static final String SECRET_HEADER = "X-Storefront-Client-Secret";
    static final String NOT_AVAILABLE_CODE = "SHOP_NOT_AVAILABLE";
    private static final long WARN_INTERVAL_MILLIS = 60_000;
    private static final Logger log = LoggerFactory.getLogger(HttpStorefrontAccessClient.class);

    /** Giới hạn log lỗi hạ tầng (bí mật sai, identity 5xx...) tối đa một dòng mỗi phút, tránh ngập log khi sự cố kéo dài. */
    private final AtomicLong lastWarnMillis = new AtomicLong();

    private final RestClient client;
    private final String clientSecret;

    public HttpStorefrontAccessClient(RestClient client, String clientSecret) {
        this.client = client;
        this.clientSecret = clientSecret;
    }

    @Override
    public Optional<StorefrontAccess> issue(String tenantCode) {
        return request(new TokenRequest(tenantCode));
    }

    /** Thân {siteKey}: KHÔNG log, KHÔNG đưa site key vào thông điệp lỗi. */
    @Override
    public Optional<StorefrontAccess> issueBySiteKey(String siteKey) {
        return request(new SiteKeyRequest(siteKey));
    }

    private Optional<StorefrontAccess> request(Object requestBody) {
        try {
            StorefrontAccess access = client.post().uri("/internal/storefront/access-tokens")
                    .header(SECRET_HEADER, clientSecret)
                    .body(requestBody)
                    .retrieve().body(StorefrontAccess.class);
            if (access == null || access.accessToken() == null || access.tenantId() == null) {
                throw PublicApiException.serviceUnavailable();
            }
            return Optional.of(access);
        } catch (RestClientResponseException ex) {
            // Chỉ 404 có code SHOP_NOT_AVAILABLE mới là "không khả dụng" (và bị đệm phủ định). 404 khác (URL sai, proxy,
            // HTML, thân rỗng) là lỗi hạ tầng/cấu hình: 503 chung, không đệm, để không che sự cố bằng 404 hàng loạt.
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND) && NOT_AVAILABLE_CODE.equals(DownstreamErrorCode.of(ex))) {
                return Optional.empty();
            }
            // 401 (bí mật sai/rỗng), 5xx... đều là lỗi cấu hình/hạ tầng: 503 chung, không lộ chi tiết.
            warnLimited("identity từ chối cấp token storefront, status=" + ex.getStatusCode().value());
            throw PublicApiException.serviceUnavailable();
        } catch (RestClientException ex) {
            warnLimited("Không gọi được identity: " + ex.getClass().getSimpleName());
            throw PublicApiException.serviceUnavailable();
        }
    }

    private void warnLimited(String message) {
        long now = System.currentTimeMillis();
        long last = lastWarnMillis.get();
        if (now - last >= WARN_INTERVAL_MILLIS && lastWarnMillis.compareAndSet(last, now)) {
            log.warn("{} (log tối đa một dòng mỗi phút)", message);
        }
    }

    record TokenRequest(String tenantCode) {
    }

    record SiteKeyRequest(String siteKey) {

        @Override
        public String toString() {
            return "SiteKeyRequest[siteKey=***]";
        }
    }
}

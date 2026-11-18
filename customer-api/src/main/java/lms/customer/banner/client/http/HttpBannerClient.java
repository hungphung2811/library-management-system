package lms.customer.banner.client.http;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import lms.customer.banner.client.BannerClient;
import lms.customer.banner.dto.BannerDtos.BannerView;
import lms.customer.web.DownstreamErrorCode;
import lms.customer.web.PublicApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * GET {@code /internal/sales/storefront/banners} của sales. Ánh xạ từng trường từ record upstream sang DTO công khai
 * (trường thừa không lọt ra). Phòng thủ thêm: {@code linkPath} không hợp lệ (không bắt đầu bằng đúng một "/", có scheme,
 * "//", "\" hoặc khoảng trắng) bị bỏ thành null thay vì chuyển tiếp. Lỗi 401/403 khác MODULE_NOT_ENABLED/TENANT_REQUIRED
 * là lỗi hạ tầng: 503 chung (như HttpCatalogClient).
 */
public class HttpBannerClient implements BannerClient {

    static final int MAX_BANNERS = 10;
    private static final Set<String> SHOP_UNAVAILABLE_CODES = Set.of("MODULE_NOT_ENABLED", "TENANT_REQUIRED");
    private static final Pattern SAFE_LINK = Pattern.compile("^/(?!/)[A-Za-z0-9\\-._~/?#=&+,%]*$");
    private static final Logger log = LoggerFactory.getLogger(HttpBannerClient.class);

    private final RestClient client;

    public HttpBannerClient(RestClient client) {
        this.client = client;
    }

    @Override
    public List<BannerView> list(String serviceToken) {
        List<UpstreamBanner> body;
        try {
            body = client.get().uri("/internal/sales/storefront/banners")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken).retrieve()
                    .body(new ParameterizedTypeReference<List<UpstreamBanner>>() { });
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == HttpStatus.UNAUTHORIZED.value() || (status == HttpStatus.FORBIDDEN.value()
                    && !DownstreamErrorCode.isOneOf(SHOP_UNAVAILABLE_CODES, DownstreamErrorCode.of(ex)))) {
                log.warn("sales từ chối token dịch vụ storefront (banner), status={}", status);
                throw PublicApiException.serviceUnavailable();
            }
            throw ex;
        }
        if (body == null) {
            throw PublicApiException.serviceUnavailable();
        }
        return body.stream().limit(MAX_BANNERS)
                .map(b -> new BannerView(b.id(), b.title(), b.subtitle(), safeLink(b.linkPath()), b.sortOrder()))
                .toList();
    }

    static String safeLink(String link) {
        if (link == null || link.length() > 200 || !SAFE_LINK.matcher(link).matches() || link.contains("..")) {
            return null;
        }
        return link;
    }

    record UpstreamBanner(UUID id, String title, String subtitle, String linkPath, int sortOrder) {
    }
}

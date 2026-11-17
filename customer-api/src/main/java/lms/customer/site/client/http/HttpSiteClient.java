package lms.customer.site.client.http;

import java.util.List;
import lms.customer.site.client.SiteClient;
import lms.customer.site.dto.SiteDtos.ContactSubmission;
import lms.customer.site.dto.SiteDtos.PageSummaryView;
import lms.customer.site.dto.SiteDtos.PageView;
import org.springframework.core.ParameterizedTypeReference;
import lms.customer.web.DownstreamErrorCode;
import lms.customer.web.PublicApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Gọi /internal/cms/public/** của system-service (không cần token: endpoint @PublicEndpoint).
 * Đã đối chiếu với CmsPublicController (B-C3): 3 đường dẫn, body/response khớp. Mã lỗi: 404 CMS_PAGE_NOT_FOUND,
 * 400 CONSENT_REQUIRED/CONTACT_REQUEST_INVALID/VALIDATION_ERROR; PublicExceptionHandler ánh xạ sang mã công khai.
 */
public class HttpSiteClient implements SiteClient {

    private final RestClient client;

    public HttpSiteClient(RestClient client) {
        this.client = client;
    }

    @Override
    public List<PageSummaryView> listPages() {
        List<PageSummaryView> result = client.get().uri("/internal/cms/public/pages").retrieve()
                .body(new ParameterizedTypeReference<List<PageSummaryView>>() { });
        return result == null ? List.of() : result;
    }

    @Override
    public PageView getPage(String slug) {
        try {
            PageView page = client.get().uri("/internal/cms/public/pages/{slug}", slug).retrieve().body(PageView.class);
            if (page == null) {
                throw PublicApiException.serviceUnavailable();
            }
            return page;
        } catch (RestClientResponseException ex) {
            // 404 CMS_PAGE_NOT_FOUND thật thành lỗi công khai để CachedSiteClient đệm phủ định; 404 khác (URL sai, proxy)
            // giữ nguyên là lỗi hạ tầng.
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND) && "CMS_PAGE_NOT_FOUND".equals(DownstreamErrorCode.of(ex))) {
                throw new PublicApiException(HttpStatus.NOT_FOUND, "CMS_PAGE_NOT_FOUND", "Không tìm thấy trang");
            }
            throw ex;
        }
    }

    @Override
    public void submitContactRequest(ContactSubmission submission) {
        client.post().uri("/internal/cms/public/contact-requests").body(submission).retrieve().toBodilessEntity();
    }
}

package lms.customer.site.web;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import lms.customer.site.client.SiteClient;
import lms.customer.site.dto.SiteDtos.ContactRequest;
import lms.customer.site.dto.SiteDtos.ContactSubmission;
import lms.customer.site.dto.SiteDtos.PageSummaryView;
import lms.customer.site.dto.SiteDtos.PageView;
import lms.customer.web.PublicApiException;
import lms.customer.web.PublicCache;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Trang giới thiệu SaaS (chỉ bản đã xuất bản) và form liên hệ/dùng thử. Công khai, không cần đăng nhập. */
@RestController
@RequestMapping("/public/site")
public class SiteController {

    static final Duration PAGE_MAX_AGE = Duration.ofSeconds(60);
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9-]{1,80}$");

    private final SiteClient site;
    private final ObjectProvider<MeterRegistry> metrics;

    public SiteController(SiteClient site, ObjectProvider<MeterRegistry> metrics) {
        this.site = site;
        this.metrics = metrics;
    }

    @GetMapping("/pages")
    public ResponseEntity<List<PageSummaryView>> pages() {
        return PublicCache.publicFor(PAGE_MAX_AGE, site.listPages());
    }

    @GetMapping("/pages/{slug}")
    public ResponseEntity<PageView> page(@PathVariable String slug) {
        if (!SLUG.matcher(slug).matches()) {
            throw new PublicApiException(HttpStatus.NOT_FOUND, "CMS_PAGE_NOT_FOUND", "Không tìm thấy trang");
        }
        PageView page = site.getPage(slug);
        if (page == null) {
            // system-service trả 200 thân rỗng/null: không trả 200 rỗng cho khách, 503 chung.
            throw PublicApiException.serviceUnavailable();
        }
        return PublicCache.publicFor(PAGE_MAX_AGE, page);
    }

    /**
     * 201 không thân (không trả lại dữ liệu cá nhân). Honeypot {@code website} có giá trị: vẫn 201 nhưng không chuyển
     * tới system-service, chỉ tăng bộ đếm metric (không log nội dung).
     */
    @PostMapping("/contact-requests")
    public ResponseEntity<Void> contact(@Valid @RequestBody ContactRequest request) {
        if (!Boolean.TRUE.equals(request.consent())) {
            throw new PublicApiException(HttpStatus.BAD_REQUEST, "CONSENT_REQUIRED",
                    "Cần đồng ý xử lý dữ liệu để gửi biểu mẫu");
        }
        if (request.website() != null && !request.website().isBlank()) {
            MeterRegistry registry = metrics.getIfAvailable();
            if (registry != null) {
                registry.counter("customer.contact.honeypot").increment();
            }
            return created();
        }
        site.submitContactRequest(new ContactSubmission(request.fullName().trim(), request.email().trim(),
                blankToNull(request.phone()), request.organization().trim(), request.businessType(),
                request.message().trim(), true));
        return created();
    }

    private static ResponseEntity<Void> created() {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).build();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

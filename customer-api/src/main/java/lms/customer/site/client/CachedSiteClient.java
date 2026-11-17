package lms.customer.site.client;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.List;
import lms.customer.site.dto.SiteDtos.ContactSubmission;
import lms.customer.site.dto.SiteDtos.PageSummaryView;
import lms.customer.site.dto.SiteDtos.PageView;
import lms.customer.web.PublicApiException;
import org.springframework.http.HttpStatus;

/**
 * Đệm nội dung giới thiệu 60 giây (ADR-0011 2.11: system render lúc đọc, customer-api đệm). Kết quả thành công đệm theo
 * {@code ttl}; trang không tồn tại (404 CMS_PAGE_NOT_FOUND) đệm phủ định ngắn (tối đa 20 giây, có trần kích thước) để
 * slug ngẫu nhiên hợp lệ không biến mỗi request thành một lời gọi sang system-service. Lỗi 503 không bị đệm.
 * Việc gửi form luôn đi thẳng.
 */
public class CachedSiteClient implements SiteClient {

    private static final String LIST_KEY = "*list*";
    static final Duration MAX_NEGATIVE_TTL = Duration.ofSeconds(20);
    private static final String NOT_FOUND_CODE = "CMS_PAGE_NOT_FOUND";

    private final SiteClient delegate;
    private final Cache<String, List<PageSummaryView>> lists;
    private final Cache<String, PageView> pages;
    private final Cache<String, Boolean> missing;

    public CachedSiteClient(SiteClient delegate, Duration ttl, int maxSize) {
        this(delegate, ttl, maxSize, Ticker.systemTicker());
    }

    public CachedSiteClient(SiteClient delegate, Duration ttl, int maxSize, Ticker ticker) {
        this.delegate = delegate;
        this.lists = Caffeine.newBuilder().maximumSize(1).expireAfterWrite(ttl).recordStats().ticker(ticker).build();
        this.pages = Caffeine.newBuilder().maximumSize(maxSize).expireAfterWrite(ttl).recordStats().ticker(ticker).build();
        Duration negativeTtl = ttl.compareTo(MAX_NEGATIVE_TTL) < 0 ? ttl : MAX_NEGATIVE_TTL;
        this.missing = Caffeine.newBuilder().maximumSize(maxSize).expireAfterWrite(negativeTtl).recordStats()
                .ticker(ticker).build();
    }

    @Override
    public List<PageSummaryView> listPages() {
        return lists.get(LIST_KEY, k -> List.copyOf(delegate.listPages()));
    }

    @Override
    public PageView getPage(String slug) {
        if (missing.getIfPresent(slug) != null) {
            throw notFound();
        }
        try {
            return pages.get(slug, delegate::getPage);
        } catch (PublicApiException ex) {
            if (HttpStatus.NOT_FOUND.equals(ex.status()) && NOT_FOUND_CODE.equals(ex.code())) {
                missing.put(slug, Boolean.TRUE);
            }
            throw ex;
        }
    }

    /** Đăng ký metric Caffeine cho ba bộ đệm (tag cache=customer.site.pagelist|pages|pages.missing). */
    public void bindMetrics(MeterRegistry registry) {
        CaffeineCacheMetrics.monitor(registry, lists, "customer.site.pagelist");
        CaffeineCacheMetrics.monitor(registry, pages, "customer.site.pages");
        CaffeineCacheMetrics.monitor(registry, missing, "customer.site.pages.missing");
    }

    private static PublicApiException notFound() {
        return new PublicApiException(HttpStatus.NOT_FOUND, NOT_FOUND_CODE, "Không tìm thấy trang");
    }

    @Override
    public void submitContactRequest(ContactSubmission submission) {
        delegate.submitContactRequest(submission);
    }
}

package lms.system.cms.infrastructure.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.util.List;
import lms.cache.RequestCache;
import lms.system.cms.application.CmsPageRepositoryPort;
import lms.system.cms.application.MarkdownRendererPort;
import lms.system.cms.application.PublishedCmsPagesUseCase;

/**
 * Bọc đọc trang CMS công khai bằng cache. Trang CMS là dữ liệu cấp nền tảng (system V3: không tenant_id, không RLS)
 * nên dùng {@code getGlobal}. Cache cả HTML đã render (tránh render lại Markdown). Không cache lỗi 404 (loader ném).
 *
 * <p>Chưa có evict cho vùng global trong cache-common: sau khi admin xuất bản/ẩn/xóa/sửa, trang công khai có thể cũ tối
 * đa {@link #TTL}.
 */
public class CachedPublishedCmsPagesUseCase extends PublishedCmsPagesUseCase {

    public static final String NS_LIST = "system.cms-public-pages";
    public static final String NS_PAGE = "system.cms-public-page";
    public static final Duration TTL = Duration.ofSeconds(60);

    private final RequestCache cache;

    public CachedPublishedCmsPagesUseCase(CmsPageRepositoryPort repository, MarkdownRendererPort renderer,
                                          RequestCache cache) {
        super(repository, renderer);
        this.cache = cache;
    }

    @Override
    public List<Summary> list() {
        return cache.getGlobal(NS_LIST, "all", TTL, new TypeReference<List<Summary>>() { }, super::list);
    }

    @Override
    public Published get(String slug) {
        return cache.getGlobal(NS_PAGE, slug, TTL, Published.class, () -> super.get(slug));
    }
}

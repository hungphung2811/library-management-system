package lms.system.cms.infrastructure.cache;

import java.time.Clock;
import java.util.UUID;
import lms.cache.RequestCache;
import lms.system.cms.application.CmsPageRepositoryPort;
import lms.system.cms.application.ManageCmsPagesUseCase;
import lms.system.cms.domain.CmsPage;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sau mọi thao tác ghi trang CMS, xóa cache trang công khai (vùng global). Evict được hoãn tới sau commit nếu đang
 * trong giao dịch; ghi lỗi (ném ngoại lệ) thì không evict vì dữ liệu không đổi.
 */
public class EvictingManageCmsPagesUseCase extends ManageCmsPagesUseCase {

    private final RequestCache cache;

    public EvictingManageCmsPagesUseCase(CmsPageRepositoryPort repository, Clock clock, RequestCache cache) {
        super(repository, clock);
        this.cache = cache;
    }

    @Override
    @Transactional
    public CmsPage create(CreateCommand command) {
        return evicting(super.create(command));
    }

    @Override
    @Transactional
    public CmsPage update(UpdateCommand command) {
        return evicting(super.update(command));
    }

    @Override
    @Transactional
    public CmsPage publish(UUID id, long version, UUID by) {
        return evicting(super.publish(id, version, by));
    }

    @Override
    @Transactional
    public CmsPage unpublish(UUID id, UUID by) {
        return evicting(super.unpublish(id, by));
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        super.delete(id);
        evictPublic();
    }

    private CmsPage evicting(CmsPage page) {
        evictPublic();
        return page;
    }

    private void evictPublic() {
        cache.evictGlobalNamespace(CachedPublishedCmsPagesUseCase.NS_LIST);
        cache.evictGlobalNamespace(CachedPublishedCmsPagesUseCase.NS_PAGE);
    }
}

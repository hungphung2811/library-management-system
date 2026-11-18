package lms.catalog.catalog.infrastructure.cache;

import lms.cache.RequestCache;
import lms.catalog.catalog.application.imports.ReferenceCachePort;
import lms.catalog.catalog.domain.ReferenceKind;

/** Xóa namespace cache danh mục của tenant hiện tại (cùng namespace mà {@link CachedReferenceUseCases} dùng). */
public class RequestCacheReferenceCachePort implements ReferenceCachePort {

    private final RequestCache cache;

    public RequestCacheReferenceCachePort(RequestCache cache) {
        this.cache = cache;
    }

    @Override
    public void evict(ReferenceKind kind) {
        cache.evictNamespace(CachedReferenceUseCases.namespace(kind));
    }
}

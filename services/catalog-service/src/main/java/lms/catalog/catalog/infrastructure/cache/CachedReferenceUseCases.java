package lms.catalog.catalog.infrastructure.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lms.cache.RequestCache;
import lms.catalog.catalog.application.CreateReferenceUseCase;
import lms.catalog.catalog.application.GetReferenceUseCase;
import lms.catalog.catalog.application.ReferenceOption;
import lms.catalog.catalog.application.ReferenceOptionsUseCase;
import lms.catalog.catalog.application.ReferencePageResponse;
import lms.catalog.catalog.application.ReferenceResponse;
import lms.catalog.catalog.application.ReferenceSearchQuery;
import lms.catalog.catalog.application.ReferenceUseCases;
import lms.catalog.catalog.application.SearchReferencesUseCase;
import lms.catalog.catalog.application.SetReferenceStatusUseCase;
import lms.catalog.catalog.application.UpdateReferenceUseCase;
import lms.catalog.catalog.domain.ReferenceExtras;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceRepository;

/**
 * Bọc use case danh mục tham chiếu (tác giả, nhà xuất bản, thể loại, vị trí kệ) bằng {@link RequestCache}. Mỗi loại một
 * namespace theo tenant ({@code catalog.authors}, {@code catalog.publishers}, {@code catalog.categories},
 * {@code catalog.shelf-locations}); ghi (tạo/sửa/đổi trạng thái) xóa cả namespace của loại đó. Dữ liệu cũ tối đa
 * {@link #TTL} khi nguồn đổi bằng đường khác (seed, SQL). Không dùng cho tiêu đề/bản sao (số bản sao sẵn có phải thật).
 */
public final class CachedReferenceUseCases {

    public static final Duration TTL = Duration.ofSeconds(60);

    private CachedReferenceUseCases() {
    }

    public static String namespace(ReferenceKind kind) {
        return switch (kind) {
            case AUTHOR -> "catalog.authors";
            case PUBLISHER -> "catalog.publishers";
            case CATEGORY -> "catalog.categories";
            case SHELF_LOCATION -> "catalog.shelf-locations";
        };
    }

    public static ReferenceUseCases create(ReferenceRepository repo, RequestCache cache) {
        return new ReferenceUseCases(new EvictingCreate(repo, cache), new EvictingUpdate(repo, cache),
                new EvictingSetStatus(repo, cache), new CachedGet(repo, cache), new CachedSearch(repo, cache),
                new CachedOptions(repo, cache));
    }

    static final class CachedGet extends GetReferenceUseCase {
        private final RequestCache cache;

        CachedGet(ReferenceRepository repo, RequestCache cache) {
            super(repo);
            this.cache = cache;
        }

        @Override
        public ReferenceResponse execute(ReferenceKind kind, UUID id) {
            return cache.get(namespace(kind), "get:" + id, TTL, ReferenceResponse.class,
                    () -> super.execute(kind, id));
        }
    }

    static final class CachedSearch extends SearchReferencesUseCase {
        private final RequestCache cache;

        CachedSearch(ReferenceRepository repo, RequestCache cache) {
            super(repo);
            this.cache = cache;
        }

        @Override
        public ReferencePageResponse execute(ReferenceKind kind, ReferenceSearchQuery query) {
            return cache.get(namespace(kind), "search:" + query, TTL, ReferencePageResponse.class,
                    () -> super.execute(kind, query));
        }
    }

    static final class CachedOptions extends ReferenceOptionsUseCase {
        private final RequestCache cache;

        CachedOptions(ReferenceRepository repo, RequestCache cache) {
            super(repo);
            this.cache = cache;
        }

        @Override
        public List<ReferenceOption> execute(ReferenceKind kind, String keyword) {
            return cache.get(namespace(kind), "options:" + keyword, TTL, new TypeReference<List<ReferenceOption>>() { },
                    () -> super.execute(kind, keyword));
        }
    }

    static final class EvictingCreate extends CreateReferenceUseCase {
        private final RequestCache cache;

        EvictingCreate(ReferenceRepository repo, RequestCache cache) {
            super(repo);
            this.cache = cache;
        }

        @Override
        public ReferenceResponse execute(ReferenceKind kind, String name, String description, UUID parentId) {
            ReferenceResponse r = super.execute(kind, name, description, parentId);
            cache.evictNamespace(namespace(kind));
            return r;
        }

        @Override
        public ReferenceResponse execute(ReferenceKind kind, String name, String description, UUID parentId,
                                         ReferenceExtras extras) {
            ReferenceResponse r = super.execute(kind, name, description, parentId, extras);
            cache.evictNamespace(namespace(kind));
            return r;
        }
    }

    static final class EvictingUpdate extends UpdateReferenceUseCase {
        private final RequestCache cache;

        EvictingUpdate(ReferenceRepository repo, RequestCache cache) {
            super(repo);
            this.cache = cache;
        }

        @Override
        public ReferenceResponse execute(ReferenceKind kind, UUID id, String name, String description, UUID parentId) {
            ReferenceResponse r = super.execute(kind, id, name, description, parentId);
            cache.evictNamespace(namespace(kind));
            return r;
        }

        @Override
        public ReferenceResponse execute(ReferenceKind kind, UUID id, String name, String description, UUID parentId,
                                         ReferenceExtras extras) {
            ReferenceResponse r = super.execute(kind, id, name, description, parentId, extras);
            cache.evictNamespace(namespace(kind));
            return r;
        }
    }

    static final class EvictingSetStatus extends SetReferenceStatusUseCase {
        private final RequestCache cache;

        EvictingSetStatus(ReferenceRepository repo, RequestCache cache) {
            super(repo);
            this.cache = cache;
        }

        @Override
        public ReferenceResponse execute(ReferenceKind kind, UUID id, boolean active) {
            ReferenceResponse r = super.execute(kind, id, active);
            cache.evictNamespace(namespace(kind));
            return r;
        }
    }
}

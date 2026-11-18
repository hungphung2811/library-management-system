package lms.identity.department.infrastructure.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lms.cache.RequestCache;
import lms.identity.department.application.ChangeDepartmentStatusUseCase;
import lms.identity.department.application.CreateDepartmentRequest;
import lms.identity.department.application.CreateDepartmentUseCase;
import lms.identity.department.application.DepartmentNode;
import lms.identity.department.application.DepartmentResponse;
import lms.identity.department.application.ListDepartmentsUseCase;
import lms.identity.department.application.UpdateDepartmentRequest;
import lms.identity.department.application.UpdateDepartmentUseCase;
import lms.identity.department.domain.DepartmentRepository;
import lms.identity.department.domain.DepartmentStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decorator cache cho danh sách/cây phòng ban (GET quản trị) và evict ở các use case ghi. Cache theo tenant hiện tại
 * (RequestCache tự gắn tenant). Không đụng {@code GetDepartmentUseCase} vì context user dùng chung để kiểm tra khi gán.
 */
public final class CachedDepartmentUseCases {

    public static final String NAMESPACE = "identity.departments";
    static final Duration TTL = Duration.ofSeconds(30);

    private CachedDepartmentUseCases() {
    }

    public static class CachedList extends ListDepartmentsUseCase {
        private final RequestCache cache;

        public CachedList(DepartmentRepository repository, RequestCache cache) {
            super(repository);
            this.cache = cache;
        }

        @Override
        public List<DepartmentResponse> list(DepartmentStatus status) {
            return cache.get(NAMESPACE, "list:" + (status == null ? "ALL" : status.name()), TTL,
                    new TypeReference<List<DepartmentResponse>>() { }, () -> super.list(status));
        }

        @Override
        public List<DepartmentNode> tree(boolean includeInactive) {
            return cache.get(NAMESPACE, "tree:" + includeInactive, TTL,
                    new TypeReference<List<DepartmentNode>>() { }, () -> super.tree(includeInactive));
        }
    }

    public static class EvictingCreate extends CreateDepartmentUseCase {
        private final RequestCache cache;

        public EvictingCreate(DepartmentRepository repository, RequestCache cache) {
            super(repository);
            this.cache = cache;
        }

        @Override
        @Transactional
        public DepartmentResponse execute(CreateDepartmentRequest request) {
            DepartmentResponse response = super.execute(request);
            cache.evictNamespace(NAMESPACE);
            return response;
        }
    }

    public static class EvictingUpdate extends UpdateDepartmentUseCase {
        private final RequestCache cache;

        public EvictingUpdate(DepartmentRepository repository, RequestCache cache) {
            super(repository);
            this.cache = cache;
        }

        @Override
        @Transactional
        public DepartmentResponse execute(UUID id, UpdateDepartmentRequest request) {
            DepartmentResponse response = super.execute(id, request);
            cache.evictNamespace(NAMESPACE);
            return response;
        }
    }

    public static class EvictingChangeStatus extends ChangeDepartmentStatusUseCase {
        private final RequestCache cache;

        public EvictingChangeStatus(DepartmentRepository repository, RequestCache cache) {
            super(repository);
            this.cache = cache;
        }

        @Override
        @Transactional
        public DepartmentResponse execute(UUID id, DepartmentStatus target) {
            DepartmentResponse response = super.execute(id, target);
            cache.evictNamespace(NAMESPACE);
            return response;
        }
    }
}

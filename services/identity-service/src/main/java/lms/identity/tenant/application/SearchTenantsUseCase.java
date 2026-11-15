package lms.identity.tenant.application;

import java.util.Map;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantCriteria;
import lms.identity.tenant.domain.TenantPage;
import lms.identity.tenant.domain.TenantQueryRepository;

/** Danh sách và tìm kiếm tenant (mã, tên, trường, trạng thái), phân trang; mỗi dòng có số thành viên ACTIVE. */
public class SearchTenantsUseCase {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 100;

    private final TenantQueryRepository queries;

    public SearchTenantsUseCase(TenantQueryRepository queries) {
        this.queries = queries;
    }

    public PageResponse<TenantResponse> execute(TenantSearchRequest request) {
        int page = request.page() == null ? 0 : Math.max(0, request.page());
        int size = request.size() == null ? DEFAULT_SIZE : Math.min(MAX_SIZE, Math.max(1, request.size()));
        String keyword = request.keyword() == null || request.keyword().isBlank() ? null : request.keyword().trim();
        TenantPage result = queries.search(new TenantCriteria(keyword, request.status(), request.organizationId(),
                page, size));
        Map<java.util.UUID, Long> counts = queries.countActiveMembers(result.items().stream().map(Tenant::id).toList());
        return new PageResponse<>(result.items().stream()
                .map(t -> TenantResponse.from(t, counts.getOrDefault(t.id(), 0L))).toList(),
                result.total(), page, size);
    }
}

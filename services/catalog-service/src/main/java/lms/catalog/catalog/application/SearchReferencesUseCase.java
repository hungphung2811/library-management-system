package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.ReferenceKind;
import lms.catalog.catalog.domain.ReferenceRepository;
import org.springframework.transaction.annotation.Transactional;

/** Tìm danh mục có phân trang theo từ khóa (tên/mô tả), lọc trạng thái và cha. */
public class SearchReferencesUseCase {

    private final ReferenceRepository repository;

    public SearchReferencesUseCase(ReferenceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public ReferencePageResponse execute(ReferenceKind kind, ReferenceSearchQuery query) {
        int page = query.pageOrDefault();
        int size = query.sizeOrDefault();
        ReferenceRepository.Page result = repository.search(kind, query.keyword(), query.active(), query.parentId(),
                page, size);
        return new ReferencePageResponse(GetReferenceUseCase.toResponses(repository, result.items()), result.total(),
                page, size);
    }
}

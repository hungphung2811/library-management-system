package lms.catalog.catalog.application;

import lms.catalog.catalog.application.WorkDtos.SearchWorksQuery;
import lms.catalog.catalog.application.WorkDtos.WorkPageResponse;
import lms.catalog.catalog.domain.WorkRepository;
import org.springframework.transaction.annotation.Transactional;

/** Tìm tác phẩm có phân trang (không cache: kèm số bản sao sẵn có). Số truy vấn không phụ thuộc kích thước trang. */
public class SearchWorksUseCase {

    private final WorkRepository works;
    private final WorkService workService;

    public SearchWorksUseCase(WorkRepository works, WorkService workService) {
        this.works = works;
        this.workService = workService;
    }

    @Transactional(readOnly = true)
    public WorkPageResponse execute(SearchWorksQuery query) {
        int page = query.pageOrDefault();
        int size = query.sizeOrDefault();
        WorkRepository.Page result = works.search(
                new WorkRepository.Criteria(query.keyword(), query.seriesId(), query.categoryId(), query.authorId()),
                page, size);
        return new WorkPageResponse(workService.summaries(result.items()), result.total(), page, size);
    }
}

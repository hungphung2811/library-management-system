package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.TitleRepository;
import java.util.List;

/** Tra đầu sách theo từ khóa (tên/tác giả), tối đa {@value #MAX_RESULTS} kết quả theo tên, cùng dạng với lookup theo id. */
public class LookupTitlesByKeywordUseCase {

    public static final int MAX_RESULTS = 200;

    private final TitleRepository repository;
    private final WorkService workService;

    public LookupTitlesByKeywordUseCase(TitleRepository repository) {
        this(repository, null);
    }

    public LookupTitlesByKeywordUseCase(TitleRepository repository, WorkService workService) {
        this.repository = repository;
        this.workService = workService;
    }

    public List<TitleLookupView> execute(String keyword) {
        List<lms.catalog.catalog.domain.Title> found = repository.searchLimited(keyword, MAX_RESULTS);
        return workService == null ? found.stream().map(TitleLookupView::from).toList()
                : workService.lookupViews(found);
    }
}

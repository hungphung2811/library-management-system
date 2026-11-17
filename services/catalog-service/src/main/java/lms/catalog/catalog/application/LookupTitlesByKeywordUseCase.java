package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.TitleRepository;
import java.util.List;

/** Tra đầu sách theo từ khóa (tên/tác giả), tối đa {@value #MAX_RESULTS} kết quả theo tên, cùng dạng với lookup theo id. */
public class LookupTitlesByKeywordUseCase {

    public static final int MAX_RESULTS = 200;

    private final TitleRepository repository;

    public LookupTitlesByKeywordUseCase(TitleRepository repository) {
        this.repository = repository;
    }

    public List<TitleLookupView> execute(String keyword) {
        return repository.searchLimited(keyword, MAX_RESULTS).stream().map(TitleLookupView::from).toList();
    }
}

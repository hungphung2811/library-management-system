package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleRepository;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tra nhiều đầu sách theo ISBN trong một lần (một truy vấn IN, không N+1). ISBN được trim; ISBN không có trong
 * tenant thì vắng mặt trong kết quả. Thứ tự kết quả theo thứ tự ISBN yêu cầu, ISBN trùng chỉ trả một lần.
 */
public class LookupTitlesByIsbnUseCase {

    private final TitleRepository repository;

    public LookupTitlesByIsbnUseCase(TitleRepository repository) {
        this.repository = repository;
    }

    public List<TitleLookupView> execute(List<String> isbns) {
        if (isbns == null || isbns.isEmpty()) {
            return List.of();
        }
        if (isbns.size() > LookupTitlesByIsbnRequest.MAX_ISBNS) {
            throw new IllegalArgumentException("Tối đa " + LookupTitlesByIsbnRequest.MAX_ISBNS + " ISBN mỗi lần tra");
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String isbn : isbns) {
            String trimmed = Objects.requireNonNull(isbn, "ISBN null").trim();
            if (!trimmed.isEmpty()) {
                distinct.add(trimmed);
            }
        }
        if (distinct.isEmpty()) {
            return List.of();
        }
        Map<String, Title> byIsbn = new HashMap<>();
        for (Title t : repository.findByIsbns(distinct)) {
            byIsbn.put(t.isbn(), t);
        }
        return distinct.stream().map(byIsbn::get).filter(Objects::nonNull).map(TitleLookupView::from).toList();
    }
}

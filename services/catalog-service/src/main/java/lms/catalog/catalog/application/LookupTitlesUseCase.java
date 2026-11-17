package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleRepository;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Tra nhiều đầu sách theo id trong một lần. Id không tồn tại hoặc thuộc tenant khác (bộ lọc tenant của
 * repository) thì không có trong kết quả. Thứ tự kết quả theo thứ tự id yêu cầu, id trùng chỉ trả một lần.
 */
public class LookupTitlesUseCase {

    private final TitleRepository repository;

    public LookupTitlesUseCase(TitleRepository repository) {
        this.repository = repository;
    }

    public List<TitleLookupView> execute(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() > LookupTitlesRequest.MAX_IDS) {
            throw new IllegalArgumentException("Tối đa " + LookupTitlesRequest.MAX_IDS + " id mỗi lần tra");
        }
        LinkedHashSet<UUID> distinct = new LinkedHashSet<>();
        for (UUID id : ids) {
            distinct.add(Objects.requireNonNull(id, "id đầu sách null"));
        }
        Map<UUID, Title> found = new LinkedHashMap<>();
        for (Title t : repository.findByIds(distinct)) {
            found.put(t.id(), t);
        }
        return distinct.stream().map(found::get).filter(Objects::nonNull).map(TitleLookupView::from).toList();
    }
}

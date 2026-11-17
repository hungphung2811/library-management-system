package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleRepository;
import lms.catalog.inventory.application.CopyAvailabilityQuery;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** UC02: tìm đầu sách theo tên/tác giả, kèm số bản sao sẵn có; tùy chọn chỉ lấy đầu sách còn bản sao. */
public class SearchTitlesUseCase {

    private final TitleRepository repository;
    private final CopyAvailabilityQuery availability;
    private final TitleClassificationService classification;

    public SearchTitlesUseCase(TitleRepository repository, CopyAvailabilityQuery availability) {
        this(repository, availability, null);
    }

    public SearchTitlesUseCase(TitleRepository repository, CopyAvailabilityQuery availability,
                               TitleClassificationService classification) {
        this.repository = repository;
        this.availability = availability;
        this.classification = classification;
    }

    public List<TitleResponse> execute(String keyword, boolean onlyAvailable) {
        return execute(keyword, onlyAvailable, null, null);
    }

    /** Lọc thêm theo thể loại và nhà xuất bản (khớp đúng id, null = không lọc). */
    public List<TitleResponse> execute(String keyword, boolean onlyAvailable, UUID categoryId, UUID publisherId) {
        List<Title> titles = categoryId == null && publisherId == null ? repository.search(keyword)
                : repository.searchFiltered(keyword, categoryId, publisherId);
        Map<UUID, Long> counts = availability.countAvailable(titles.stream().map(Title::id).toList());
        List<Title> kept = titles.stream().filter(t -> !onlyAvailable || counts.getOrDefault(t.id(), 0L) > 0).toList();
        Map<UUID, TitleClassificationService.View> views = classification == null ? Map.of()
                : classification.describe(kept);
        return kept.stream()
                .map(t -> TitleResponse.from(t, counts.getOrDefault(t.id(), 0L), views.get(t.id())))
                .toList();
    }
}

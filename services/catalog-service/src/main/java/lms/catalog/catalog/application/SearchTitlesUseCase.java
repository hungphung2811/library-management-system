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

    public SearchTitlesUseCase(TitleRepository repository, CopyAvailabilityQuery availability) {
        this.repository = repository;
        this.availability = availability;
    }

    public List<TitleResponse> execute(String keyword, boolean onlyAvailable) {
        List<Title> titles = repository.search(keyword);
        Map<UUID, Long> counts = availability.countAvailable(titles.stream().map(Title::id).toList());
        return titles.stream()
                .filter(t -> !onlyAvailable || counts.getOrDefault(t.id(), 0L) > 0)
                .map(t -> TitleResponse.from(t, counts.getOrDefault(t.id(), 0L)))
                .toList();
    }
}

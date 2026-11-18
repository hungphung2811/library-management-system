package lms.catalog.catalog.application;

import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleRepository;
import lms.catalog.catalog.domain.WorkRepository;
import lms.catalog.inventory.application.CopyAvailabilityQuery;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * UC02: tìm ấn bản (đầu sách) theo tên/tác giả, kèm số bản sao sẵn có; tùy chọn chỉ lấy ấn bản còn bản sao, lọc theo
 * thể loại, nhà xuất bản, tác phẩm (workId) và bộ sách (seriesId, ADR-0026).
 */
public class SearchTitlesUseCase {

    private final TitleRepository repository;
    private final CopyAvailabilityQuery availability;
    private final TitleResponseAssembler assembler;
    private final WorkRepository works;

    public SearchTitlesUseCase(TitleRepository repository, CopyAvailabilityQuery availability) {
        this(repository, availability, null);
    }

    public SearchTitlesUseCase(TitleRepository repository, CopyAvailabilityQuery availability,
                               TitleClassificationService classification) {
        this(repository, availability, new TitleResponseAssembler(availability, classification, null), null);
    }

    public SearchTitlesUseCase(TitleRepository repository, CopyAvailabilityQuery availability,
                               TitleResponseAssembler assembler, WorkRepository works) {
        this.repository = repository;
        this.availability = availability;
        this.assembler = assembler;
        this.works = works;
    }

    public List<TitleResponse> execute(String keyword, boolean onlyAvailable) {
        return execute(keyword, onlyAvailable, null, null);
    }

    /** Lọc thêm theo thể loại và nhà xuất bản (khớp đúng id, null = không lọc). */
    public List<TitleResponse> execute(String keyword, boolean onlyAvailable, UUID categoryId, UUID publisherId) {
        return execute(keyword, onlyAvailable, categoryId, publisherId, null, null);
    }

    /** Lọc thêm theo tác phẩm và bộ sách (khớp đúng id, null = không lọc). */
    public List<TitleResponse> execute(String keyword, boolean onlyAvailable, UUID categoryId, UUID publisherId,
                                       UUID workId, UUID seriesId) {
        List<Title> titles = workId != null || seriesId != null
                ? byWork(keyword, categoryId, publisherId, workId, seriesId)
                : categoryId == null && publisherId == null ? repository.search(keyword)
                        : repository.searchFiltered(keyword, categoryId, publisherId);
        Map<UUID, Long> counts = availability.countAvailable(titles.stream().map(Title::id).toList());
        List<Title> kept = titles.stream().filter(t -> !onlyAvailable || counts.getOrDefault(t.id(), 0L) > 0).toList();
        return assembler.assemble(kept, counts);
    }

    /** Lọc theo tác phẩm/bộ sách: nạp ấn bản của các tác phẩm đó rồi lọc phần còn lại trong bộ nhớ (tập nhỏ). */
    private List<Title> byWork(String keyword, UUID categoryId, UUID publisherId, UUID workId, UUID seriesId) {
        Set<UUID> workIds = new HashSet<>();
        if (seriesId != null && works != null) workIds.addAll(works.findIdsBySeries(seriesId));
        if (workId != null) {
            if (seriesId != null) workIds.retainAll(Set.of(workId));
            else workIds.add(workId);
        }
        String kw = keyword == null ? "" : keyword.strip().toLowerCase(java.util.Locale.ROOT);
        return repository.findByWorkIds(workIds).stream()
                .filter(t -> kw.isEmpty() || t.name().toLowerCase(java.util.Locale.ROOT).contains(kw)
                        || t.author().toLowerCase(java.util.Locale.ROOT).contains(kw))
                .filter(t -> categoryId == null || categoryId.equals(t.categoryId()))
                .filter(t -> publisherId == null || publisherId.equals(t.publisherId()))
                .toList();
    }
}

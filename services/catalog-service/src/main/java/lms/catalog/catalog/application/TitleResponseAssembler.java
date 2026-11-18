package lms.catalog.catalog.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import lms.catalog.catalog.domain.Title;
import lms.catalog.inventory.application.CopyAvailabilityQuery;

/**
 * Dựng {@link TitleResponse} cho một danh sách ấn bản với số truy vấn không phụ thuộc kích thước danh sách (mục 3.11 ADR-0026):
 * phủ trường cấp tác phẩm (works, work_authors, series, đếm bản sao theo tác phẩm: 4 truy vấn), đếm bản sao theo ấn bản (1)
 * và tra tên nhà xuất bản/thể loại/tác giả (3). {@code classification} và {@code workService} có thể null (cấu hình cũ/test).
 */
public class TitleResponseAssembler {

    private final CopyAvailabilityQuery availability;
    private final TitleClassificationService classification;
    private final WorkService workService;

    public TitleResponseAssembler(CopyAvailabilityQuery availability, TitleClassificationService classification,
                                  WorkService workService) {
        this.availability = availability;
        this.classification = classification;
        this.workService = workService;
    }

    /** Dựng phản hồi; {@code counts} (số bản sao sẵn có theo ấn bản) null thì tự đếm. */
    public List<TitleResponse> assemble(List<Title> source, Map<UUID, Long> counts) {
        if (source.isEmpty()) return List.of();
        WorkService.Enriched enriched = workService == null ? new WorkService.Enriched(source, Map.of())
                : workService.enrich(source);
        List<Title> titles = enriched.titles();
        Map<UUID, Long> available = counts != null ? counts
                : availability.countAvailable(titles.stream().map(Title::id).toList());
        Map<UUID, TitleClassificationService.View> views = classification == null ? Map.of()
                : classification.describe(titles);
        return titles.stream().map(t -> {
            long n = available.getOrDefault(t.id(), 0L);
            TitleResponse base = classification == null ? TitleResponse.from(t, n)
                    : TitleResponse.from(t, n, views.get(t.id()));
            return base.withWork(enriched.info().get(t.id()));
        }).toList();
    }

    public TitleResponse assemble(Title title) {
        return assemble(List.of(title), null).get(0);
    }
}

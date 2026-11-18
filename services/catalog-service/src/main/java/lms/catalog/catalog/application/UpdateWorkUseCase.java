package lms.catalog.catalog.application;

import java.util.UUID;
import lms.catalog.catalog.application.WorkDtos.SaveWorkRequest;
import lms.catalog.catalog.application.WorkDtos.WorkDetail;
import lms.catalog.catalog.domain.Work;
import lms.catalog.catalog.domain.WorkNotFoundException;
import lms.catalog.catalog.domain.WorkRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sửa tác phẩm (thay toàn bộ). Trường cấp tác phẩm được ghi gương sang cột cũ của mọi ấn bản trong cùng transaction
 * (giai đoạn chuyển tiếp, ADR-0026 mục 4.3). Giá trị đang có trên tác phẩm giữ được dù mục tham chiếu đã ngừng dùng.
 */
public class UpdateWorkUseCase {

    private final WorkRepository works;
    private final WorkService workService;
    private final TitleClassificationService classification;

    public UpdateWorkUseCase(WorkRepository works, WorkService workService, TitleClassificationService classification) {
        this.works = works;
        this.workService = workService;
        this.classification = classification;
    }

    @Transactional
    public WorkDetail execute(UUID id, SaveWorkRequest r) {
        Work current = works.findById(id).orElseThrow(() -> new WorkNotFoundException(id));
        workService.requireCategoryUsable(r.categoryId(), current.categoryId());
        workService.requireSeriesUsable(r.seriesId(), current.seriesId());
        workService.requireAuthorsUsable(r.authorIds(), current.authorIds());
        String author = classification.authorText(r.author(), r.authorIds());
        Work next = current.update(r.title(), r.originalTitle(), r.originalLanguage(), author, r.description(),
                r.categoryId(), r.seriesId(), r.seriesNumber(), r.seriesLabel(), r.kind(), r.authorIds());
        Work saved = works.save(next);
        workService.mirror(saved);
        return workService.detail(saved);
    }
}

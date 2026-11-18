package lms.catalog.catalog.application;

import lms.catalog.catalog.application.WorkDtos.SaveWorkRequest;
import lms.catalog.catalog.application.WorkDtos.WorkDetail;
import lms.catalog.catalog.domain.Work;
import lms.catalog.catalog.domain.WorkRepository;
import org.springframework.transaction.annotation.Transactional;

/** Tạo tác phẩm (ADR-0026). Thể loại, bộ sách, tác giả chuẩn phải tồn tại cùng tenant và đang hoạt động. */
public class CreateWorkUseCase {

    private final WorkRepository works;
    private final WorkService workService;
    private final TitleClassificationService classification;

    public CreateWorkUseCase(WorkRepository works, WorkService workService, TitleClassificationService classification) {
        this.works = works;
        this.workService = workService;
        this.classification = classification;
    }

    @Transactional
    public WorkDetail execute(SaveWorkRequest request) {
        return workService.detail(create(request));
    }

    /** Dựng, kiểm tham chiếu và ghi tác phẩm; chạy trong transaction của người gọi (dùng chung với tách ấn bản). */
    public Work create(SaveWorkRequest r) {
        workService.requireCategoryUsable(r.categoryId(), null);
        workService.requireSeriesUsable(r.seriesId(), null);
        workService.requireAuthorsUsable(r.authorIds(), java.util.List.of());
        String author = classification.authorText(r.author(), r.authorIds());
        Work work = Work.create(r.title(), r.originalTitle(), r.originalLanguage(), author, r.description(),
                r.categoryId(), r.seriesId(), r.seriesNumber(), r.seriesLabel(), r.kind(), r.authorIds());
        return works.save(work);
    }
}

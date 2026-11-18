package lms.catalog.catalog.application;

import java.util.UUID;
import lms.catalog.catalog.application.WorkDtos.WorkDetail;
import lms.catalog.catalog.domain.WorkNotFoundException;
import lms.catalog.catalog.domain.WorkRepository;
import org.springframework.transaction.annotation.Transactional;

/** Chi tiết tác phẩm kèm danh sách ấn bản và số bản sao sẵn có của từng ấn bản. */
public class GetWorkUseCase {

    private final WorkRepository works;
    private final WorkService workService;

    public GetWorkUseCase(WorkRepository works, WorkService workService) {
        this.works = works;
        this.workService = workService;
    }

    @Transactional(readOnly = true)
    public WorkDetail execute(UUID id) {
        return workService.detail(works.findById(id).orElseThrow(() -> new WorkNotFoundException(id)));
    }
}

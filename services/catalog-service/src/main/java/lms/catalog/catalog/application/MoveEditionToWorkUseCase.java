package lms.catalog.catalog.application;

import java.util.List;
import java.util.UUID;
import lms.catalog.catalog.application.WorkDtos.ChangeEditionWorkRequest;
import lms.catalog.catalog.domain.InvalidWorkException;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleNotFoundException;
import lms.catalog.catalog.domain.TitleRepository;
import lms.catalog.catalog.domain.Work;
import lms.catalog.catalog.domain.WorkNotFoundException;
import lms.catalog.catalog.domain.WorkRepository;
import lms.catalog.inventory.application.PublishEditionWorkChangedUseCase.Change;
import org.springframework.transaction.annotation.Transactional;

/**
 * Đổi tác phẩm của một ấn bản (ADR-0026): gắn vào tác phẩm có sẵn ({@code workId}) hoặc tách ra tác phẩm mới
 * ({@code newWork}). Ấn bản mang trường cấp tác phẩm của tác phẩm đích (ghi gương cột cũ). Tác phẩm cũ không còn ấn bản
 * nào thì bị xóa (tác phẩm rỗng không có giá trị; chốt của be-dev, ADR để mở). Trả kèm thay đổi để người gọi phát
 * {@code EditionWorkChangedEvent} SAU commit.
 */
public class MoveEditionToWorkUseCase {

    /** Kết quả: ấn bản sau khi đổi và thay đổi cần phát (rỗng nếu không đổi gì). */
    public record Result(TitleResponse title, List<Change> changes) {
    }

    private final TitleRepository titles;
    private final WorkRepository works;
    private final WorkService workService;
    private final CreateWorkUseCase createWork;
    private final TitleResponseAssembler assembler;

    public MoveEditionToWorkUseCase(TitleRepository titles, WorkRepository works, WorkService workService,
                                    CreateWorkUseCase createWork, TitleResponseAssembler assembler) {
        this.titles = titles;
        this.works = works;
        this.workService = workService;
        this.createWork = createWork;
        this.assembler = assembler;
    }

    @Transactional
    public Result execute(UUID titleId, ChangeEditionWorkRequest request) {
        boolean hasId = request.workId() != null;
        boolean hasNew = request.newWork() != null;
        if (hasId == hasNew) {
            throw new InvalidWorkException("Chỉ gửi một trong hai: workId (gắn vào tác phẩm có sẵn) hoặc newWork (tách ra tác phẩm mới)");
        }
        Title title = titles.findById(titleId).orElseThrow(() -> new TitleNotFoundException(titleId));
        UUID oldWorkId = title.workId();
        Work target = hasId
                ? works.findById(request.workId()).orElseThrow(() -> new WorkNotFoundException(request.workId()))
                : createWork.create(request.newWork());
        if (target.id().equals(oldWorkId)) {
            return new Result(assembler.assemble(title), List.of());
        }
        WorkService.WorkView view = workService.views(List.of(target.id())).get(target.id());
        Title moved = title.withWork(target.id()).withWorkLevel(target.categoryId(), target.authorIds(),
                view == null ? null : view.seriesName(), target.seriesNumber());
        Title saved = titles.save(moved);
        if (oldWorkId != null && titles.findByWorkIds(List.of(oldWorkId)).isEmpty()) {
            works.delete(oldWorkId);
        }
        return new Result(assembler.assemble(saved), List.of(new Change(titleId, oldWorkId, target.id())));
    }
}

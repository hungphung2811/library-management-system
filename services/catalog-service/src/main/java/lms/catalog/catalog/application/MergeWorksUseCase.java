package lms.catalog.catalog.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lms.catalog.catalog.application.WorkDtos.WorkDetail;
import lms.catalog.catalog.domain.Title;
import lms.catalog.catalog.domain.TitleRepository;
import lms.catalog.catalog.domain.Work;
import lms.catalog.catalog.domain.WorkMergeSelfException;
import lms.catalog.catalog.domain.WorkNotFoundException;
import lms.catalog.catalog.domain.WorkRepository;
import lms.catalog.inventory.application.PublishEditionWorkChangedUseCase.Change;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gộp các tác phẩm nguồn vào tác phẩm đích (ADR-0026): chuyển mọi ấn bản sang tác phẩm đích, hợp tác giả chuẩn, ghi gương
 * trường cấp tác phẩm, xóa tác phẩm nguồn; MỘT transaction trong catalog_db. Trả kèm danh sách ấn bản đã chuyển để người
 * gọi phát sự kiện SAU commit.
 */
public class MergeWorksUseCase {

    public static final int MAX_SOURCES = 20;

    /** Kết quả gộp: chi tiết tác phẩm đích và các ấn bản đã đổi tác phẩm. */
    public record Result(WorkDetail detail, List<Change> changes) {
    }

    private final WorkRepository works;
    private final TitleRepository titles;
    private final WorkService workService;

    public MergeWorksUseCase(WorkRepository works, TitleRepository titles, WorkService workService) {
        this.works = works;
        this.titles = titles;
        this.workService = workService;
    }

    @Transactional
    public Result execute(UUID targetId, List<UUID> sourceWorkIds) {
        Set<UUID> sources = new LinkedHashSet<>(sourceWorkIds);
        if (sources.contains(targetId)) throw new WorkMergeSelfException(targetId);
        if (sources.size() > MAX_SOURCES) {
            throw new IllegalArgumentException("Tối đa " + MAX_SOURCES + " tác phẩm nguồn mỗi lần gộp");
        }
        Set<UUID> all = new LinkedHashSet<>(sources);
        all.add(targetId);
        Map<UUID, Work> found = works.findByIds(all).stream().collect(Collectors.toMap(Work::id, w -> w));
        for (UUID id : all) {
            if (!found.containsKey(id)) throw new WorkNotFoundException(id);
        }
        List<Change> changes = new ArrayList<>();
        for (Title t : titles.findByWorkIds(sources)) changes.add(new Change(t.id(), t.workId(), targetId));
        titles.moveToWork(sources, targetId);

        List<UUID> moreAuthors = new ArrayList<>();
        for (UUID id : sources) moreAuthors.addAll(found.get(id).authorIds());
        Work target = works.save(found.get(targetId).withMergedAuthors(moreAuthors));
        for (UUID id : sources) works.delete(id);
        workService.mirror(target);
        return new Result(workService.detail(target), changes);
    }
}

package lms.catalog.catalog.infrastructure.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lms.catalog.catalog.application.CreateWorkUseCase;
import lms.catalog.catalog.application.GetWorkUseCase;
import lms.catalog.catalog.application.MergeWorksUseCase;
import lms.catalog.catalog.application.SearchWorksUseCase;
import lms.catalog.catalog.application.UpdateWorkUseCase;
import lms.catalog.catalog.application.WorkDtos.MergeWorksRequest;
import lms.catalog.catalog.application.WorkDtos.SaveWorkRequest;
import lms.catalog.catalog.application.WorkDtos.SearchWorksQuery;
import lms.catalog.catalog.application.WorkDtos.WorkDetail;
import lms.catalog.catalog.application.WorkDtos.WorkPageResponse;
import lms.catalog.inventory.application.PublishEditionWorkChangedUseCase;
import lms.security.permission.CrossTenantReadable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tác phẩm (ADR-0026): nội dung trừu tượng gom nhiều ấn bản. Khóa quyền sinh tự động: WorkController/search, get, create,
 * update, merge. KHÔNG @StorefrontAccessible ở v1 (storefront gộp biến thể từ dữ liệu sales).
 */
@RestController
@RequestMapping("/internal/works")
public class WorkController {

    private final SearchWorksUseCase searchWorks;
    private final GetWorkUseCase getWork;
    private final CreateWorkUseCase createWork;
    private final UpdateWorkUseCase updateWork;
    private final MergeWorksUseCase mergeWorks;
    private final PublishEditionWorkChangedUseCase editionEvents;

    public WorkController(SearchWorksUseCase searchWorks, GetWorkUseCase getWork, CreateWorkUseCase createWork,
                          UpdateWorkUseCase updateWork, MergeWorksUseCase mergeWorks,
                          PublishEditionWorkChangedUseCase editionEvents) {
        this.searchWorks = searchWorks;
        this.getWork = getWork;
        this.createWork = createWork;
        this.updateWork = updateWork;
        this.mergeWorks = mergeWorks;
        this.editionEvents = editionEvents;
    }

    /** Tìm tác phẩm có phân trang (keyword, seriesId, categoryId, authorId, page từ 0, size 1..100). */
    @GetMapping
    @CrossTenantReadable
    public WorkPageResponse search(SearchWorksQuery query) {
        return searchWorks.execute(query);
    }

    @GetMapping("/{id}")
    public WorkDetail get(@PathVariable UUID id) {
        return getWork.execute(id);
    }

    @PostMapping
    public ResponseEntity<WorkDetail> create(@Valid @RequestBody SaveWorkRequest request) {
        WorkDetail created = createWork.execute(request);
        return ResponseEntity.created(URI.create("/internal/works/" + created.id())).body(created);
    }

    /** Thay toàn bộ trường tác phẩm; trường cấp tác phẩm được ghi gương sang mọi ấn bản. */
    @PutMapping("/{id}")
    public WorkDetail update(@PathVariable UUID id, @Valid @RequestBody SaveWorkRequest request) {
        return updateWork.execute(id, request);
    }

    /** Gộp các tác phẩm nguồn vào tác phẩm này (trả tác phẩm đích). Sự kiện đổi tác phẩm phát sau khi commit. */
    @PostMapping("/{id}/merge")
    public WorkDetail merge(@PathVariable UUID id, @Valid @RequestBody MergeWorksRequest request) {
        MergeWorksUseCase.Result result = mergeWorks.execute(id, request.sourceWorkIds());
        editionEvents.execute(result.changes());
        return result.detail();
    }
}

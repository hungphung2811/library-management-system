package lms.api.catalog.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import lms.api.catalog.client.WorkCatalogClient;
import lms.api.catalog.dto.WorkDtos.MergeWorksRequest;
import lms.api.catalog.dto.WorkDtos.SaveWorkRequest;
import lms.api.catalog.dto.WorkDtos.WorkDetailView;
import lms.api.catalog.dto.WorkDtos.WorkPageView;
import lms.api.catalog.dto.WorkDtos.WorkSearchQuery;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tác phẩm (ADR-0026): /api/works. Proxy sang catalog-service; quyền do catalog kiểm (khóa WorkController/search, get,
 * create, update, merge). Đổi tác phẩm của một ấn bản là PUT /api/books/{id}/work (CatalogController).
 */
@RestController
@RequestMapping("/api/works")
public class WorkController {

    private final WorkCatalogClient works;

    public WorkController(WorkCatalogClient works) {
        this.works = works;
    }

    @GetMapping
    public WorkPageView search(WorkSearchQuery query) {
        return works.search(query);
    }

    @GetMapping("/{id}")
    public WorkDetailView get(@PathVariable UUID id) {
        return works.get(id);
    }

    @PostMapping
    public ResponseEntity<WorkDetailView> create(@Valid @RequestBody SaveWorkRequest request) {
        WorkDetailView created = works.create(request);
        return ResponseEntity.created(URI.create("/api/works/" + created.id())).body(created);
    }

    /** Thay toàn bộ; thể loại, bộ sách, tác giả được ghi gương sang mọi ấn bản của tác phẩm. */
    @PutMapping("/{id}")
    public WorkDetailView update(@PathVariable UUID id, @Valid @RequestBody SaveWorkRequest request) {
        return works.update(id, request);
    }

    /** Gộp các tác phẩm nguồn vào tác phẩm id (đích); trả tác phẩm đích. */
    @PostMapping("/{id}/merge")
    public WorkDetailView merge(@PathVariable UUID id, @Valid @RequestBody MergeWorksRequest request) {
        return works.merge(id, request);
    }
}

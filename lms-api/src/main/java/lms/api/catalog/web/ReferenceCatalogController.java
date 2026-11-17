package lms.api.catalog.web;

import lms.api.catalog.client.ReferenceCatalogClient;
import lms.api.catalog.dto.ReferenceOptionView;
import lms.api.catalog.dto.ReferencePageView;
import lms.api.catalog.dto.ReferenceSearchQuery;
import lms.api.catalog.dto.ReferenceView;
import lms.api.catalog.dto.SaveReferenceRequest;
import lms.api.catalog.dto.SaveShelfLocationRequest;
import lms.api.catalog.dto.SetReferenceStatusRequest;
import lms.api.catalog.dto.ShelfLocationOptionView;
import lms.api.catalog.dto.ShelfLocationPageView;
import lms.api.catalog.dto.ShelfLocationView;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Danh mục chuẩn cho FE: /api/authors, /api/publishers, /api/categories, /api/shelf-locations. Proxy sang catalog-service;
 * quyền do catalog-service kiểm (khóa AuthorController/search, PublisherController/create...).
 */
@RestController
@RequestMapping("/api")
public class ReferenceCatalogController {

    private static final String NAMED = "{resource:authors|publishers|categories}";

    private final ReferenceCatalogClient references;

    public ReferenceCatalogController(ReferenceCatalogClient references) {
        this.references = references;
    }

    @GetMapping("/" + NAMED)
    public ReferencePageView search(@PathVariable String resource, ReferenceSearchQuery query) {
        return references.search(resource, query);
    }

    @GetMapping("/" + NAMED + "/options")
    public List<ReferenceOptionView> options(@PathVariable String resource,
                                             @RequestParam(required = false) String keyword) {
        return references.options(resource, keyword);
    }

    @GetMapping("/" + NAMED + "/{id}")
    public ReferenceView get(@PathVariable String resource, @PathVariable UUID id) {
        return references.get(resource, id);
    }

    @PostMapping("/" + NAMED)
    public ResponseEntity<ReferenceView> create(@PathVariable String resource,
                                                @Valid @RequestBody SaveReferenceRequest request) {
        ReferenceView created = references.create(resource, request);
        return ResponseEntity.created(URI.create("/api/" + resource + "/" + created.id())).body(created);
    }

    @PutMapping("/" + NAMED + "/{id}")
    public ReferenceView update(@PathVariable String resource, @PathVariable UUID id,
                                @Valid @RequestBody SaveReferenceRequest request) {
        return references.update(resource, id, request);
    }

    /** Bật/ngừng sử dụng (thay cho xóa cứng). */
    @PutMapping("/" + NAMED + "/{id}/status")
    public ReferenceView setStatus(@PathVariable String resource, @PathVariable UUID id,
                                   @Valid @RequestBody SetReferenceStatusRequest request) {
        return references.setStatus(resource, id, request.active());
    }

    @GetMapping("/shelf-locations")
    public ShelfLocationPageView searchShelfLocations(ReferenceSearchQuery query) {
        return references.searchShelfLocations(query);
    }

    @GetMapping("/shelf-locations/options")
    public List<ShelfLocationOptionView> shelfLocationOptions(@RequestParam(required = false) String keyword) {
        return references.shelfLocationOptions(keyword);
    }

    @GetMapping("/shelf-locations/{id}")
    public ShelfLocationView getShelfLocation(@PathVariable UUID id) {
        return references.getShelfLocation(id);
    }

    @PostMapping("/shelf-locations")
    public ResponseEntity<ShelfLocationView> createShelfLocation(@Valid @RequestBody SaveShelfLocationRequest request) {
        ShelfLocationView created = references.createShelfLocation(request);
        return ResponseEntity.created(URI.create("/api/shelf-locations/" + created.id())).body(created);
    }

    @PutMapping("/shelf-locations/{id}")
    public ShelfLocationView updateShelfLocation(@PathVariable UUID id,
                                                 @Valid @RequestBody SaveShelfLocationRequest request) {
        return references.updateShelfLocation(id, request);
    }

    @PutMapping("/shelf-locations/{id}/status")
    public ShelfLocationView setShelfLocationStatus(@PathVariable UUID id,
                                                    @Valid @RequestBody SetReferenceStatusRequest request) {
        return references.setShelfLocationStatus(id, request.active());
    }
}

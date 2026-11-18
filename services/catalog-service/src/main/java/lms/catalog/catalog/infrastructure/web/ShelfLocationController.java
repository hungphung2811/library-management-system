package lms.catalog.catalog.infrastructure.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lms.catalog.catalog.application.ReferenceSearchQuery;
import lms.catalog.catalog.application.ReferenceUseCases;
import lms.catalog.catalog.application.SaveShelfLocationRequest;
import lms.catalog.catalog.application.SetReferenceStatusRequest;
import lms.catalog.catalog.application.ShelfLocationOption;
import lms.catalog.catalog.application.ShelfLocationPageResponse;
import lms.catalog.catalog.application.ShelfLocationResponse;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.security.permission.RequiresModule;
import lms.security.permission.TenantModule;
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
 * Vị trí kệ (mã + mô tả) gắn với bản sao, thuộc module LENDING như BookCopyController. Khóa quyền sinh tự động:
 * ShelfLocationController/search, options, get, create, update, setStatus.
 */
@RestController
@RequiresModule(TenantModule.LENDING)
@RequestMapping("/internal/shelf-locations")
public class ShelfLocationController {

    private static final ReferenceKind KIND = ReferenceKind.SHELF_LOCATION;

    private final ReferenceUseCases useCases;

    public ShelfLocationController(ReferenceUseCases useCases) {
        this.useCases = useCases;
    }

    @GetMapping
    public ShelfLocationPageResponse search(ReferenceSearchQuery query) {
        return ShelfLocationPageResponse.from(useCases.search().execute(KIND, query));
    }

    /** Danh sách rút gọn (id, code) các vị trí đang active; tối đa 200. */
    @GetMapping("/options")
    public List<ShelfLocationOption> options(@RequestParam(required = false) String keyword) {
        return useCases.options().execute(KIND, keyword).stream()
                .map(o -> new ShelfLocationOption(o.id(), o.name())).toList();
    }

    @GetMapping("/{id}")
    public ShelfLocationResponse get(@PathVariable UUID id) {
        return ShelfLocationResponse.from(useCases.get().execute(KIND, id));
    }

    @PostMapping
    public ResponseEntity<ShelfLocationResponse> create(@Valid @RequestBody SaveShelfLocationRequest request) {
        ShelfLocationResponse created = ShelfLocationResponse.from(
                useCases.create().execute(KIND, request.code(), request.description(), null, request.extras()));
        return ResponseEntity.created(URI.create("/internal/shelf-locations/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public ShelfLocationResponse update(@PathVariable UUID id, @Valid @RequestBody SaveShelfLocationRequest request) {
        return ShelfLocationResponse.from(
                useCases.update().execute(KIND, id, request.code(), request.description(), null,
                        request.extras()));
    }

    @PutMapping("/{id}/status")
    public ShelfLocationResponse setStatus(@PathVariable UUID id, @Valid @RequestBody SetReferenceStatusRequest request) {
        return ShelfLocationResponse.from(useCases.setStatus().execute(KIND, id, request.active()));
    }
}

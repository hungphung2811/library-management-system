package lms.catalog.catalog.infrastructure.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lms.catalog.catalog.application.ReferenceOption;
import lms.catalog.catalog.application.SeriesResponse;
import lms.catalog.catalog.application.ReferencePage;
import lms.catalog.catalog.application.ReferenceSearchQuery;
import lms.catalog.catalog.application.ReferenceUseCases;
import lms.catalog.catalog.application.SaveNamedReferenceRequest;
import lms.catalog.catalog.application.SetReferenceStatusRequest;
import lms.catalog.catalog.domain.ReferenceKind;
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
 * Danh mục bộ sách (ADR-0026) theo tenant. Khóa quyền sinh tự động: SeriesController/search, options, get, create, update, setStatus.
 */
@RestController
@RequestMapping("/internal/series")
public class SeriesController {

    private static final ReferenceKind KIND = ReferenceKind.SERIES;

    private final ReferenceUseCases useCases;

    public SeriesController(ReferenceUseCases useCases) {
        this.useCases = useCases;
    }

    /** Tìm có phân trang (keyword theo tên/mô tả, active, page từ 0, size 1..100). */
    @GetMapping
    public ReferencePage<SeriesResponse> search(ReferenceSearchQuery query) {
        return ReferencePage.of(useCases.search().execute(KIND, query), SeriesResponse::from);
    }

    /** Danh sách rút gọn (id, tên) các mục đang active cho ô chọn; tối đa 200. */
    @GetMapping("/options")
    public List<ReferenceOption> options(@RequestParam(required = false) String keyword) {
        return useCases.options().execute(KIND, keyword);
    }

    @GetMapping("/{id}")
    public SeriesResponse get(@PathVariable UUID id) {
        return SeriesResponse.from(useCases.get().execute(KIND, id));
    }

    @PostMapping
    public ResponseEntity<SeriesResponse> create(@Valid @RequestBody SaveNamedReferenceRequest request) {
        SeriesResponse created = SeriesResponse.from(
                useCases.create().execute(KIND, request.name(), request.description(), null, null));
        return ResponseEntity.created(URI.create("/internal/series/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public SeriesResponse update(@PathVariable UUID id, @Valid @RequestBody SaveNamedReferenceRequest request) {
        return SeriesResponse.from(useCases.update().execute(KIND, id, request.name(), request.description(), null,
                null));
    }

    /** Bật/ngừng sử dụng (thay cho xóa cứng). */
    @PutMapping("/{id}/status")
    public SeriesResponse setStatus(@PathVariable UUID id, @Valid @RequestBody SetReferenceStatusRequest request) {
        return SeriesResponse.from(useCases.setStatus().execute(KIND, id, request.active()));
    }
}

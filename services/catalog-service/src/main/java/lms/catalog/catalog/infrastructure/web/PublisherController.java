package lms.catalog.catalog.infrastructure.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lms.catalog.catalog.application.ReferenceOption;
import lms.catalog.catalog.application.PublisherResponse;
import lms.catalog.catalog.application.ReferencePage;
import lms.catalog.catalog.application.ReferenceSearchQuery;
import lms.catalog.catalog.application.ReferenceUseCases;
import lms.catalog.catalog.application.SavePublisherRequest;
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
 * Danh mục nhà xuất bản chuẩn theo tenant. Khóa quyền sinh tự động: PublisherController/search, options, get, create, update, setStatus.
 */
@RestController
@RequestMapping("/internal/publishers")
public class PublisherController {

    private static final ReferenceKind KIND = ReferenceKind.PUBLISHER;

    private final ReferenceUseCases useCases;

    public PublisherController(ReferenceUseCases useCases) {
        this.useCases = useCases;
    }

    /** Tìm có phân trang (keyword theo tên/mô tả, active, page từ 0, size 1..100). */
    @GetMapping
    public ReferencePage<PublisherResponse> search(ReferenceSearchQuery query) {
        return ReferencePage.of(useCases.search().execute(KIND, query), PublisherResponse::from);
    }

    /** Danh sách rút gọn (id, tên) các mục đang active cho ô chọn; tối đa 200. */
    @GetMapping("/options")
    public List<ReferenceOption> options(@RequestParam(required = false) String keyword) {
        return useCases.options().execute(KIND, keyword);
    }

    @GetMapping("/{id}")
    public PublisherResponse get(@PathVariable UUID id) {
        return PublisherResponse.from(useCases.get().execute(KIND, id));
    }

    @PostMapping
    public ResponseEntity<PublisherResponse> create(@Valid @RequestBody SavePublisherRequest request) {
        PublisherResponse created = PublisherResponse.from(
                useCases.create().execute(KIND, request.name(), request.description(), null, request.extras()));
        return ResponseEntity.created(URI.create("/internal/publishers/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public PublisherResponse update(@PathVariable UUID id, @Valid @RequestBody SavePublisherRequest request) {
        return PublisherResponse.from(useCases.update().execute(KIND, id, request.name(), request.description(), null,
                request.extras()));
    }

    /** Bật/ngừng sử dụng (thay cho xóa cứng). */
    @PutMapping("/{id}/status")
    public PublisherResponse setStatus(@PathVariable UUID id, @Valid @RequestBody SetReferenceStatusRequest request) {
        return PublisherResponse.from(useCases.setStatus().execute(KIND, id, request.active()));
    }
}

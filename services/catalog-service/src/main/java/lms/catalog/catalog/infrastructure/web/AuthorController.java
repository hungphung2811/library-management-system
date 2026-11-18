package lms.catalog.catalog.infrastructure.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lms.catalog.catalog.application.ReferenceOption;
import lms.catalog.catalog.application.AuthorResponse;
import lms.catalog.catalog.application.ReferencePage;
import lms.catalog.catalog.application.ReferenceSearchQuery;
import lms.catalog.catalog.application.ReferenceUseCases;
import lms.catalog.catalog.application.SaveAuthorRequest;
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
 * Danh mục tác giả chuẩn theo tenant. Khóa quyền sinh tự động: AuthorController/search, options, get, create, update, setStatus.
 */
@RestController
@RequestMapping("/internal/authors")
public class AuthorController {

    private static final ReferenceKind KIND = ReferenceKind.AUTHOR;

    private final ReferenceUseCases useCases;

    public AuthorController(ReferenceUseCases useCases) {
        this.useCases = useCases;
    }

    /** Tìm có phân trang (keyword theo tên/mô tả, active, page từ 0, size 1..100). */
    @GetMapping
    public ReferencePage<AuthorResponse> search(ReferenceSearchQuery query) {
        return ReferencePage.of(useCases.search().execute(KIND, query), AuthorResponse::from);
    }

    /** Danh sách rút gọn (id, tên) các mục đang active cho ô chọn; tối đa 200. */
    @GetMapping("/options")
    public List<ReferenceOption> options(@RequestParam(required = false) String keyword) {
        return useCases.options().execute(KIND, keyword);
    }

    @GetMapping("/{id}")
    public AuthorResponse get(@PathVariable UUID id) {
        return AuthorResponse.from(useCases.get().execute(KIND, id));
    }

    @PostMapping
    public ResponseEntity<AuthorResponse> create(@Valid @RequestBody SaveAuthorRequest request) {
        AuthorResponse created = AuthorResponse.from(
                useCases.create().execute(KIND, request.name(), request.description(), null, request.extras()));
        return ResponseEntity.created(URI.create("/internal/authors/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public AuthorResponse update(@PathVariable UUID id, @Valid @RequestBody SaveAuthorRequest request) {
        return AuthorResponse.from(useCases.update().execute(KIND, id, request.name(), request.description(), null,
                request.extras()));
    }

    /** Bật/ngừng sử dụng (thay cho xóa cứng). */
    @PutMapping("/{id}/status")
    public AuthorResponse setStatus(@PathVariable UUID id, @Valid @RequestBody SetReferenceStatusRequest request) {
        return AuthorResponse.from(useCases.setStatus().execute(KIND, id, request.active()));
    }
}

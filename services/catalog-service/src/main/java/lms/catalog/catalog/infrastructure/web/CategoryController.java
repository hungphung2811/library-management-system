package lms.catalog.catalog.infrastructure.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lms.catalog.catalog.application.ReferenceOption;
import lms.catalog.catalog.application.CategoryResponse;
import lms.catalog.catalog.application.ReferencePage;
import lms.catalog.catalog.application.ReferenceSearchQuery;
import lms.catalog.catalog.application.ReferenceUseCases;
import lms.catalog.catalog.application.SaveCategoryRequest;
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
 * Danh mục thể loại (cây tối đa 2 cấp) chuẩn theo tenant. Khóa quyền sinh tự động: CategoryController/search, options, get, create, update, setStatus.
 */
@RestController
@RequestMapping("/internal/categories")
public class CategoryController {

    private static final ReferenceKind KIND = ReferenceKind.CATEGORY;

    private final ReferenceUseCases useCases;

    public CategoryController(ReferenceUseCases useCases) {
        this.useCases = useCases;
    }

    /** Tìm có phân trang (keyword theo tên/mô tả, active, page từ 0, size 1..100). */
    @GetMapping
    public ReferencePage<CategoryResponse> search(ReferenceSearchQuery query) {
        return ReferencePage.of(useCases.search().execute(KIND, query), CategoryResponse::from);
    }

    /** Danh sách rút gọn (id, tên) các mục đang active cho ô chọn; tối đa 200. */
    @GetMapping("/options")
    public List<ReferenceOption> options(@RequestParam(required = false) String keyword) {
        return useCases.options().execute(KIND, keyword);
    }

    @GetMapping("/{id}")
    public CategoryResponse get(@PathVariable UUID id) {
        return CategoryResponse.from(useCases.get().execute(KIND, id));
    }

    @PostMapping
    public ResponseEntity<CategoryResponse> create(@Valid @RequestBody SaveCategoryRequest request) {
        CategoryResponse created = CategoryResponse.from(
                useCases.create().execute(KIND, request.name(), request.description(), request.parentId()));
        return ResponseEntity.created(URI.create("/internal/categories/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public CategoryResponse update(@PathVariable UUID id, @Valid @RequestBody SaveCategoryRequest request) {
        return CategoryResponse.from(
                useCases.update().execute(KIND, id, request.name(), request.description(), request.parentId()));
    }

    /** Bật/ngừng sử dụng (thay cho xóa cứng). */
    @PutMapping("/{id}/status")
    public CategoryResponse setStatus(@PathVariable UUID id, @Valid @RequestBody SetReferenceStatusRequest request) {
        return CategoryResponse.from(useCases.setStatus().execute(KIND, id, request.active()));
    }
}

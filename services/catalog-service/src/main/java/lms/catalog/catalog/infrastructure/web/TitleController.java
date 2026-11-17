package lms.catalog.catalog.infrastructure.web;

import lms.security.permission.CrossTenantReadable;
import lms.security.permission.StorefrontAccessible;
import lms.catalog.catalog.application.GetTitlePreviewUseCase;
import lms.catalog.catalog.application.LookupTitlesByKeywordUseCase;
import lms.catalog.catalog.application.TitleLookupView;
import lms.catalog.catalog.application.TitlePreviewResponse;
import lms.catalog.catalog.application.UpdateTitlePreviewRequest;
import lms.catalog.catalog.application.UpdateTitlePreviewUseCase;
import lms.catalog.catalog.application.CreateTitleCommand;
import lms.catalog.catalog.application.CreateTitleRequest;
import lms.catalog.catalog.application.CreateTitleUseCase;
import lms.catalog.catalog.application.GetTitleUseCase;
import lms.catalog.catalog.application.SearchTitlesUseCase;
import lms.catalog.catalog.application.TitleResponse;
import lms.catalog.catalog.application.UpdateTitleCommand;
import lms.catalog.catalog.application.UpdateTitleRequest;
import lms.catalog.catalog.application.UpdateTitleUseCase;
import org.springframework.web.bind.annotation.PutMapping;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/books")
public class TitleController {

    private final CreateTitleUseCase createTitle;
    private final GetTitleUseCase getTitle;
    private final SearchTitlesUseCase searchTitles;

    private final UpdateTitleUseCase updateTitle;
    private final lms.catalog.catalog.application.LookupTitlesUseCase lookupTitles;
    private final LookupTitlesByKeywordUseCase lookupByKeyword;
    private final GetTitlePreviewUseCase getPreview;
    private final UpdateTitlePreviewUseCase updatePreview;

    public TitleController(CreateTitleUseCase createTitle, GetTitleUseCase getTitle, SearchTitlesUseCase searchTitles,
                           UpdateTitleUseCase updateTitle,
                           lms.catalog.catalog.application.LookupTitlesUseCase lookupTitles,
                           LookupTitlesByKeywordUseCase lookupByKeyword,
                           GetTitlePreviewUseCase getPreview, UpdateTitlePreviewUseCase updatePreview) {
        this.lookupByKeyword = lookupByKeyword;
        this.getPreview = getPreview;
        this.updatePreview = updatePreview;
        this.lookupTitles = lookupTitles;
        this.createTitle = createTitle;
        this.getTitle = getTitle;
        this.searchTitles = searchTitles;
        this.updateTitle = updateTitle;
    }

    @PutMapping("/{id}")
    public TitleResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateTitleRequest request) {
        return updateTitle.execute(new UpdateTitleCommand(
                id, request.name(), request.author(), request.isbn(), request.coverPrice()));
    }

    @PostMapping
    public ResponseEntity<TitleResponse> create(@Valid @RequestBody CreateTitleRequest request) {
        TitleResponse response = createTitle.execute(new CreateTitleCommand(
                request.name(), request.author(), request.isbn(), request.coverPrice()));
        return ResponseEntity.created(URI.create("/internal/books/" + response.id())).body(response);
    }

    @GetMapping
    @CrossTenantReadable
    public List<TitleResponse> search(@RequestParam(required = false) String keyword,
                                      @RequestParam(defaultValue = "false") boolean available) {
        return searchTitles.execute(keyword, available);
    }

    /** Tra nhiều đầu sách theo id (sales-service gọi bằng token người dùng chuyển tiếp). Khóa TitleController/lookup. */
    @PostMapping("/lookup")
    @StorefrontAccessible
    public List<lms.catalog.catalog.application.TitleLookupView> lookup(
            @Valid @RequestBody lms.catalog.catalog.application.LookupTitlesRequest request) {
        return lookupTitles.execute(request.ids());
    }

    /**
     * Tra theo từ khóa (tên/tác giả), tối đa 200 đầu sách theo tên, cùng dạng kết quả với lookup (ADR-0011 2.6).
     * Khóa TitleController/lookupByKeyword (chỉ đọc, token dịch vụ storefront vào được).
     */
    @GetMapping("/lookup")
    @StorefrontAccessible
    public List<TitleLookupView> lookupByKeyword(@RequestParam(required = false) String keyword) {
        return lookupByKeyword.execute(keyword);
    }

    /** Mô tả và đoạn đọc thử (ADR-0011 2.12). Khóa TitleController/preview; token dịch vụ storefront vào được. */
    @GetMapping("/{id}/preview")
    @StorefrontAccessible
    public TitlePreviewResponse preview(@PathVariable UUID id) {
        return getPreview.execute(id);
    }

    /** Thay mô tả và đoạn đọc thử; KHÔNG @StorefrontAccessible (token dịch vụ bị 403). Khóa TitleController/updatePreview. */
    @PutMapping("/{id}/preview")
    public TitlePreviewResponse updatePreview(@PathVariable UUID id,
                                              @Valid @RequestBody UpdateTitlePreviewRequest request) {
        return updatePreview.execute(id, request.description(), request.previewText());
    }

    @GetMapping("/{id}")
    public TitleResponse get(@PathVariable UUID id) {
        return getTitle.execute(id);
    }
}

package lms.catalog.catalog.infrastructure.web;

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

    public TitleController(CreateTitleUseCase createTitle, GetTitleUseCase getTitle, SearchTitlesUseCase searchTitles,
                           UpdateTitleUseCase updateTitle) {
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
    public List<TitleResponse> search(@RequestParam(required = false) String keyword,
                                      @RequestParam(defaultValue = "false") boolean available) {
        return searchTitles.execute(keyword, available);
    }

    @GetMapping("/{id}")
    public TitleResponse get(@PathVariable UUID id) {
        return getTitle.execute(id);
    }
}

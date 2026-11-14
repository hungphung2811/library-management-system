package lms.catalog.inventory.infrastructure.web;

import lms.catalog.inventory.application.AddCopyCommand;
import lms.catalog.inventory.application.AddCopyRequest;
import lms.catalog.inventory.application.AddCopyUseCase;
import lms.catalog.inventory.application.CopyResponse;
import lms.catalog.inventory.application.ListCopiesUseCase;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/book-copies")
public class BookCopyController {

    private final AddCopyUseCase addCopy;
    private final ListCopiesUseCase listCopies;

    public BookCopyController(AddCopyUseCase addCopy, ListCopiesUseCase listCopies) {
        this.addCopy = addCopy;
        this.listCopies = listCopies;
    }

    @PostMapping
    public ResponseEntity<CopyResponse> add(@Valid @RequestBody AddCopyRequest request) {
        CopyResponse response = addCopy.execute(new AddCopyCommand(request.titleId(), request.barcode()));
        return ResponseEntity.created(URI.create("/internal/book-copies/" + response.id())).body(response);
    }

    @GetMapping
    public List<CopyResponse> list(@RequestParam UUID titleId) {
        return listCopies.execute(titleId);
    }
}

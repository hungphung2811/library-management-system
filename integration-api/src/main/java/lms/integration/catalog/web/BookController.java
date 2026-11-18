package lms.integration.catalog.web;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lms.integration.catalog.client.CatalogClient;
import lms.integration.catalog.dto.BookDtos.BookView;
import lms.integration.catalog.dto.BookDtos.LookupByIsbnRequest;
import lms.integration.catalog.dto.BookDtos.LookupRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Đầu sách (scope {@code books:read}, kiểm ở SecurityConfiguration và lại ở catalog-service). Tenant luôn là tenant
 * trong token; không có tham số tenant. Mọi phản hồi {@code Cache-Control: no-store}.
 */
@RestController
@RequestMapping("/api/integration/v1/books")
public class BookController {

    private final CatalogClient catalog;

    public BookController(CatalogClient catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/{id}")
    public ResponseEntity<BookView> get(@PathVariable UUID id) {
        return noStore(catalog.get(id));
    }

    @PostMapping("/lookup")
    public ResponseEntity<List<BookView>> lookup(@Valid @RequestBody LookupRequest request) {
        return noStore(catalog.lookup(request.ids()));
    }

    @PostMapping("/lookup-by-isbn")
    public ResponseEntity<List<BookView>> lookupByIsbn(@Valid @RequestBody LookupByIsbnRequest request) {
        return noStore(catalog.lookupByIsbn(request.isbns()));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}

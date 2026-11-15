package lms.api.catalog.web;

import lms.api.catalog.client.CatalogClient;
import lms.api.catalog.dto.AddCopyRequest;
import lms.api.catalog.dto.ChangeCopyStatusRequest;
import lms.api.catalog.dto.CopyDetailsView;
import lms.api.catalog.dto.CopyView;
import lms.api.catalog.dto.CreateTitleRequest;
import lms.api.catalog.dto.TitleView;
import lms.api.catalog.dto.UpdateTitleRequest;
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

/** API cho FE: tìm sách (UC02), quản lý đầu sách và bản sao (UC11). Quyền BOOK_VIEW/BOOK_CREATE do catalog-service kiểm. */
@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogClient catalog;

    public CatalogController(CatalogClient catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/books")
    public List<TitleView> search(@RequestParam(required = false) String keyword,
                                  @RequestParam(defaultValue = "false") boolean available) {
        return catalog.searchTitles(keyword, available);
    }

    @GetMapping("/books/{id}")
    public TitleView get(@PathVariable UUID id) {
        return catalog.getTitle(id);
    }

    @PostMapping("/books")
    public ResponseEntity<TitleView> create(@Valid @RequestBody CreateTitleRequest request) {
        TitleView created = catalog.createTitle(request);
        return ResponseEntity.created(URI.create("/api/books/" + created.id())).body(created);
    }

    /** Sửa đầu sách (tên, tác giả, ISBN, giá bìa); 404 không thấy, 409 trùng ISBN, 400/422 dữ liệu sai. */
    @PutMapping("/books/{id}")
    public TitleView update(@PathVariable UUID id, @Valid @RequestBody UpdateTitleRequest request) {
        return catalog.updateTitle(id, request);
    }

    @GetMapping("/book-copies")
    public List<CopyView> copies(@RequestParam UUID titleId) {
        return catalog.listCopies(titleId);
    }

    @PostMapping("/book-copies")
    public ResponseEntity<CopyView> addCopy(@Valid @RequestBody AddCopyRequest request) {
        CopyView created = catalog.addCopy(request);
        return ResponseEntity.created(URI.create("/api/book-copies/" + created.id())).body(created);
    }

    /** Tra bản sao theo id: trạng thái, đầu sách và giá bìa. */
    @GetMapping("/book-copies/{id}")
    public CopyDetailsView copy(@PathVariable UUID id) {
        return catalog.getCopy(id);
    }

    /** Tra bản sao theo mã vạch (thủ thư quét mã khi trả hoặc báo mất). */
    @GetMapping("/book-copies/by-barcode/{barcode}")
    public CopyDetailsView copyByBarcode(@PathVariable String barcode) {
        return catalog.getCopyByBarcode(barcode);
    }

    /** Đổi trạng thái bản sao (ví dụ DAMAGED, LOST); sai vòng đời thì catalog-service trả 409. */
    @PutMapping("/book-copies/{id}/status")
    public CopyView changeStatus(@PathVariable UUID id, @Valid @RequestBody ChangeCopyStatusRequest request) {
        return catalog.changeCopyStatus(id, request.status());
    }
}

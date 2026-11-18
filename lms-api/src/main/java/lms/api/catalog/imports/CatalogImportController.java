package lms.api.catalog.imports;

import java.io.IOException;
import lms.common.kernel.ErrorResponse;
import java.util.Set;
import java.util.UUID;
import lms.api.imports.ImportForwarder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Nhập master data catalog từ Excel (ADR-0024 mục 3.3): {@code /api/catalog/imports/{type}/...} chuyển nguyên sang
 * {@code /internal/imports/{type}/...} của catalog-service. {@code type} nằm trong danh sách trắng (không thành proxy
 * mở); quyền do catalog-service kiểm (khóa {@code TitleImportController/upload}...). Phiên, trạng thái, lỗi do service
 * quyết định; BFF chỉ chuyển byte và trạng thái HTTP.
 */
@RestController
@RequestMapping("/api/catalog/imports/{type}")
public class CatalogImportController {

    static final Set<String> TYPES = Set.of("titles", "authors", "publishers", "categories", "shelf-locations", "series");

    private final ImportForwarder forwarder;

    public CatalogImportController(ImportForwarder catalogImportForwarder) {
        this.forwarder = catalogImportForwarder;
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template(@PathVariable String type) {
        return forwarder.download(base(type) + "/template");
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<byte[]> upload(@PathVariable String type,
                                         @RequestParam(value = "file", required = false) MultipartFile file) throws IOException {
        String path = base(type);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "IMPORT_FILE_REQUIRED");
        }
        return forwarder.upload(path, file);
    }

    @GetMapping("/{importId}")
    public ResponseEntity<byte[]> get(@PathVariable String type, @PathVariable UUID importId) {
        return forwarder.download(base(type) + "/" + importId);
    }

    /** Query {@code status, sheet, page, size} chuyển nguyên chuỗi truy vấn (chỉ 4 khóa này). */
    @GetMapping("/{importId}/rows")
    public ResponseEntity<byte[]> rows(@PathVariable String type, @PathVariable UUID importId, RowsQuery query) {
        return forwarder.download(base(type) + "/" + importId + "/rows" + query.queryString());
    }

    @PostMapping("/{importId}/confirm")
    public ResponseEntity<byte[]> confirm(@PathVariable String type, @PathVariable UUID importId) {
        return forwarder.post(base(type) + "/" + importId + "/confirm", null);
    }

    @DeleteMapping("/{importId}")
    public ResponseEntity<byte[]> cancel(@PathVariable String type, @PathVariable UUID importId) {
        return forwarder.delete(base(type) + "/" + importId);
    }

    @GetMapping("/{importId}/error-file")
    public ResponseEntity<byte[]> errorFile(@PathVariable String type, @PathVariable UUID importId) {
        return forwarder.download(base(type) + "/" + importId + "/error-file");
    }

    /** Lỗi do BFF tự phát hiện (loại không hỗ trợ, thiếu file, query lạ) trả ErrorResponse chuẩn với mã IMPORT_*. */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorResponse> handleLocal(ResponseStatusException ex) {
        String code = ex.getReason() == null ? "IMPORT_REJECTED" : ex.getReason();
        return ResponseEntity.status(ex.getStatusCode()).body(ErrorResponse.of(code, switch (code) {
            case "IMPORT_FILE_REQUIRED" -> "Thiếu file .xlsx";
            case "IMPORT_TYPE_NOT_FOUND" -> "Loại nhập không được hỗ trợ";
            default -> "Tham số không hợp lệ";
        }));
    }

    private static String base(String type) {
        if (!TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "IMPORT_TYPE_NOT_FOUND");
        }
        return "/internal/imports/" + type;
    }

    /** Bộ lọc danh sách dòng: status (VALID|INVALID|DUPLICATE), sheet, page, size (tối đa 200 ở service). */
    public record RowsQuery(String status, String sheet, Integer page, Integer size) {

        String queryString() {
            StringBuilder sb = new StringBuilder();
            append(sb, "status", status);
            append(sb, "sheet", sheet);
            append(sb, "page", page == null ? null : page.toString());
            append(sb, "size", size == null ? null : size.toString());
            return sb.toString();
        }

        private static void append(StringBuilder sb, String key, String value) {
            if (value == null || value.isBlank()) return;
            // Chỉ nhận ký tự chữ-số, gạch; tránh mã hóa kép khi RestClient dựng URI và tránh chèn tham số lạ.
            if (!value.matches("[A-Za-z0-9_-]{1,40}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUERY");
            }
            sb.append(sb.length() == 0 ? '?' : '&').append(key).append('=').append(value);
        }
    }
}

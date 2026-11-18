package lms.api.identity.imports;

import java.io.IOException;
import java.util.UUID;
import lms.api.imports.ImportForwarder;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Nhập người dùng/độc giả từ Excel (ADR-0024, loại {@code users}): {@code /api/identity/imports/users/...} chuyển nguyên
 * sang {@code /internal/imports/users/...} của identity-service. Quyền do identity-service kiểm (khóa
 * {@code UserImportController/upload|confirm|credentials}); BFF chỉ chuyển byte và trạng thái HTTP. Đường dẫn cố định
 * (không nhận loại từ client) nên không thành proxy mở.
 */
@RestController
@RequestMapping("/api/identity/imports/users")
public class UserImportController {

    private static final String BASE = "/internal/imports/users";

    private final ImportForwarder forwarder;

    public UserImportController(ImportForwarder identityImportForwarder) {
        this.forwarder = identityImportForwarder;
    }

    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        return forwarder.download(BASE + "/template");
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<byte[]> upload(@RequestParam(value = "file", required = false) MultipartFile file)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "IMPORT_FILE_REQUIRED");
        }
        return forwarder.upload(BASE, file);
    }

    @GetMapping("/{importId}")
    public ResponseEntity<byte[]> get(@PathVariable UUID importId) {
        return forwarder.download(BASE + "/" + importId);
    }

    /** Query {@code status, sheet, page, size}: chỉ 4 khóa này được chuyển tiếp. */
    @GetMapping("/{importId}/rows")
    public ResponseEntity<byte[]> rows(@PathVariable UUID importId, RowsQuery query) {
        return forwarder.download(BASE + "/" + importId + "/rows" + query.queryString());
    }

    @PostMapping("/{importId}/confirm")
    public ResponseEntity<byte[]> confirm(@PathVariable UUID importId) {
        return forwarder.post(BASE + "/" + importId + "/confirm", null);
    }

    @DeleteMapping("/{importId}")
    public ResponseEntity<byte[]> cancel(@PathVariable UUID importId) {
        return forwarder.delete(BASE + "/" + importId);
    }

    @GetMapping("/{importId}/error-file")
    public ResponseEntity<byte[]> errorFile(@PathVariable UUID importId) {
        return forwarder.download(BASE + "/" + importId + "/error-file");
    }

    /** File mật khẩu tạm: tải MỘT lần sau COMMITTED (410 khi đã tải); không cho cache ở bất kỳ tầng nào. */
    @GetMapping("/{importId}/credentials")
    public ResponseEntity<byte[]> credentials(@PathVariable UUID importId) {
        ResponseEntity<byte[]> upstream = forwarder.download(BASE + "/" + importId + "/credentials");
        return ResponseEntity.status(upstream.getStatusCode()).headers(upstream.getHeaders())
                .cacheControl(CacheControl.noStore()).body(upstream.getBody());
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
            // Chỉ nhận ký tự chữ-số, gạch: tránh mã hóa kép khi RestClient dựng URI và tránh chèn tham số lạ.
            if (!value.matches("[A-Za-z0-9_-]{1,40}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUERY");
            }
            sb.append(sb.length() == 0 ? '?' : '&').append(key).append('=').append(value);
        }
    }
}

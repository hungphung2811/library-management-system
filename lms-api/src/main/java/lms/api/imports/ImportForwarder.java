package lms.api.imports;

import java.io.IOException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

/**
 * Tiện ích chuyển tiếp lời gọi nhập Excel sang service nội bộ (ADR-0024, dùng ở pha 2): chuyển multipart giữ tên
 * file, và chuyển nguyên byte (.xlsx tải về) kèm Content-Type/Content-Disposition. Dùng RestClient tạo bằng
 * {@code ServiceClients.createForImport} (timeout 30 s, tự gắn token). Lỗi 4xx/5xx của service được ném ra dưới dạng
 * RestClientResponseException để ApiExceptionHandler chuẩn hóa. KHÔNG tự quyết đường dẫn: người gọi phải kiểm theo
 * danh sách trắng module/type trước (không được thành proxy mở).
 */
public class ImportForwarder {

    /** Part multipart chứa file, khớp hợp đồng ADR-0024 mục 3.3. */
    public static final String FILE_PART = "file";

    private final RestClient client;

    public ImportForwarder(RestClient client) {
        this.client = client;
    }

    /** POST multipart/form-data (part {@code file}) tới {@code path}, trả nguyên trạng thái và byte phản hồi. */
    public ResponseEntity<byte[]> upload(String path, MultipartFile file) throws IOException {
        byte[] content = file.getBytes();
        String filename = file.getOriginalFilename() == null ? "upload.xlsx" : file.getOriginalFilename();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(file.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(file.getContentType()));
        // Không dùng MultipartBodyBuilder: nó cần reactive-streams, lms-api không có reactor.
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add(FILE_PART, new HttpEntity<>(new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        }, partHeaders));
        return passThrough(client.post().uri(path).contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts).retrieve().toEntity(byte[].class));
    }

    /** GET byte (file mẫu, file lỗi .xlsx) giữ Content-Type và Content-Disposition của service. */
    public ResponseEntity<byte[]> download(String path) {
        return passThrough(client.get().uri(path).retrieve().toEntity(byte[].class));
    }

    /** POST thân JSON nguyên byte (ví dụ confirm), trả nguyên trạng thái và byte phản hồi. */
    public ResponseEntity<byte[]> post(String path, byte[] body) {
        RestClient.RequestBodySpec spec = client.post().uri(path);
        if (body != null && body.length > 0) {
            spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return passThrough(spec.retrieve().toEntity(byte[].class));
    }

    /** DELETE (hủy phiên), trả nguyên trạng thái (204) và byte phản hồi. */
    public ResponseEntity<byte[]> delete(String path) {
        return passThrough(client.delete().uri(path).retrieve().toEntity(byte[].class));
    }

    private static ResponseEntity<byte[]> passThrough(ResponseEntity<byte[]> upstream) {
        HttpHeaders headers = new HttpHeaders();
        MediaType contentType = upstream.getHeaders().getContentType();
        if (contentType != null) {
            headers.setContentType(contentType);
        }
        String disposition = upstream.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        if (disposition != null) {
            headers.set(HttpHeaders.CONTENT_DISPOSITION, disposition);
        }
        return ResponseEntity.status(upstream.getStatusCode()).headers(headers).body(upstream.getBody());
    }
}

package lms.api.web;

import lms.common.kernel.ErrorResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Chuẩn hóa lỗi trả cho FE. 4xx của service nội bộ giữ nguyên mã và nội dung (đã là ErrorResponse chuẩn);
 * 5xx thành 502 để không lộ chi tiết nội bộ; không gọi được thành 503.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(RestClientResponseException.class)
    ResponseEntity<?> handleDownstream(RestClientResponseException ex) {
        HttpStatusCode status = ex.getStatusCode();
        if (status.is4xxClientError()) {
            String body = ex.getResponseBodyAsString();
            if (body != null && body.trim().startsWith("{")) {
                return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return ResponseEntity.status(status).body(ErrorResponse.of("DOWNSTREAM_REJECTED", "Yêu cầu bị từ chối"));
        }
        log.error("Service nội bộ lỗi {}: {}", status, ex.getResponseBodyAsString(), ex);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(ErrorResponse.of("DEPENDENCY_ERROR", "Dịch vụ nội bộ gặp lỗi, vui lòng thử lại sau"));
    }

    @ExceptionHandler(ResourceAccessException.class)
    ResponseEntity<ErrorResponse> handleUnreachable(ResourceAccessException ex) {
        log.error("Không gọi được service nội bộ", ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of("DEPENDENCY_UNAVAILABLE", "Dịch vụ nội bộ tạm thời không khả dụng"));
    }

    /** File upload vượt giới hạn multipart (5 MB, ADR-0024). */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> handleUploadTooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ErrorResponse.of("IMPORT_FILE_TOO_LARGE", "File vượt quá 5 MB"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage()).toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_FAILED", "Dữ liệu không hợp lệ", details));
    }
}

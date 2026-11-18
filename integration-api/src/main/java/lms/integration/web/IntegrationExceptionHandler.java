package lms.integration.web;

import lms.common.kernel.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Lỗi công khai thống nhất: mã + thông điệp do integration-api viết, không bao giờ lộ stack trace, tên class hay chi
 * tiết nội bộ. Lỗi không lường trước chỉ log loại exception (không log thân/header).
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class IntegrationExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(IntegrationExceptionHandler.class);

    @ExceptionHandler(IntegrationApiException.class)
    ResponseEntity<ErrorResponse> handle(IntegrationApiException e) {
        return body(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> notFound(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "NOT_FOUND", "Không tìm thấy tài nguyên");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Phương thức không được hỗ trợ");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ErrorResponse> unsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Kiểu nội dung không được hỗ trợ");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception e) {
        log.error("Lỗi không lường trước: {}", e.getClass().getName());
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Đã xảy ra lỗi, vui lòng thử lại sau");
    }

    private static ResponseEntity<ErrorResponse> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(ErrorResponse.of(code, message));
    }
}

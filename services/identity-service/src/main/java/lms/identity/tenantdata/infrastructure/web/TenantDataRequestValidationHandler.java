package lms.identity.tenantdata.infrastructure.web;

import java.util.List;
import lms.common.kernel.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Lỗi body của API xuất dữ liệu (lý do quá dài, JSON hỏng) trả {@code 400 VALIDATION_FAILED}. Giới hạn đúng hai controller
 * của context (không nuốt xử lý lỗi của context khác); không có handler thì lỗi rơi sang /error và bị denyAll thành 403.
 * Không phản chiếu giá trị đã nhập.
 */
@RestControllerAdvice(assignableTypes = {TenantDataExportController.class, PlatformTenantDataController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantDataRequestValidationHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage()).toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_FAILED", "Dữ liệu không hợp lệ", details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_FAILED", "Body không hợp lệ"));
    }
}

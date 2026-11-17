package lms.sales.shared.infrastructure.web;

import java.util.ArrayList;
import java.util.List;
import lms.common.kernel.ErrorResponse;
import lms.sales.shared.domain.DuplicateClientRequestException;
import lms.sales.shared.domain.SalesException;
import lms.tenant.TenantRequiredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Ánh xạ lỗi sang ErrorResponse (common-kernel). SalesException: HTTP status theo ErrorCode.httpStatus(), code là tên
 * enum, details dạng "khóa=giá trị" (chỉ id/số, không dữ liệu cá nhân).
 */
@RestControllerAdvice
public class SalesExceptionHandler {

    @ExceptionHandler(SalesException.class)
    ResponseEntity<ErrorResponse> handleSales(SalesException ex) {
        List<String> details = new ArrayList<>();
        ex.details().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(e -> details.add(e.getKey() + "=" + e.getValue()));
        return ResponseEntity.status(ex.code().httpStatus())
                .body(ErrorResponse.of(ex.code().name(), ex.getMessage(), details));
    }

    @ExceptionHandler(BindException.class)
    ResponseEntity<ErrorResponse> handleBind(BindException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage()).toList();
        return validation(details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return validation(List.of("Nội dung request không đọc được"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return validation(List.of(ex.getName() + ": giá trị không hợp lệ"));
    }

    @ExceptionHandler(TenantRequiredException.class)
    ResponseEntity<ErrorResponse> handleTenantRequired(TenantRequiredException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("TENANT_REQUIRED", "Token không thuộc tenant nào"));
    }

    /** Hai request cùng clientRequestId chạy đồng thời mà use case không bắt được (không nên xảy ra). */
    @ExceptionHandler(DuplicateClientRequestException.class)
    ResponseEntity<ErrorResponse> handleDuplicate(DuplicateClientRequestException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("DUPLICATE_REQUEST", "clientRequestId đã được dùng"));
    }

    private static ResponseEntity<ErrorResponse> validation(List<String> details) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_FAILED", "Dữ liệu không hợp lệ", details));
    }
}

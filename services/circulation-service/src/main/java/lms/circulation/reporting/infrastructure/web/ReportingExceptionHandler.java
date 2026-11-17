package lms.circulation.reporting.infrastructure.web;

import lms.circulation.reporting.domain.InvalidReportQueryException;
import lms.common.kernel.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Ánh xạ lỗi tham số của thống kê/báo cáo sang 400 (INVALID_RANGE, RANGE_TOO_LARGE, INVALID_GRANULARITY, INVALID_LIMIT). */
@RestControllerAdvice(basePackages = "lms.circulation.reporting")
public class ReportingExceptionHandler {

    @ExceptionHandler(InvalidReportQueryException.class)
    ResponseEntity<ErrorResponse> handleInvalidQuery(InvalidReportQueryException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(ex.code(), ex.getMessage()));
    }

    /** Ngày sai định dạng (cần yyyy-MM-dd) hoặc limit không phải số. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_FAILED", "Tham số không hợp lệ: " + ex.getName()));
    }

    /** Lỗi bind record tham số (from/to/limit sai kiểu). */
    @ExceptionHandler(org.springframework.validation.BindException.class)
    ResponseEntity<ErrorResponse> handleBind(org.springframework.validation.BindException ex) {
        String fields = ex.getFieldErrors().stream().map(e -> e.getField()).distinct().collect(java.util.stream.Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_FAILED", "Tham số không hợp lệ: " + fields));
    }
}

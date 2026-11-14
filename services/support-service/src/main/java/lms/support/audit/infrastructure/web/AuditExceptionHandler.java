package lms.support.audit.infrastructure.web;

import lms.common.kernel.ErrorResponse;
import lms.support.audit.domain.InvalidAuditQueryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class AuditExceptionHandler {

    @ExceptionHandler(InvalidAuditQueryException.class)
    ResponseEntity<ErrorResponse> handleInvalidQuery(InvalidAuditQueryException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of("INVALID_AUDIT_QUERY", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("INVALID_AUDIT_QUERY", "Tham số không hợp lệ: " + ex.getName()));
    }
}

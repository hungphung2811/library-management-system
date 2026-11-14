package lms.identity.department.infrastructure.web;

import lms.common.kernel.ErrorResponse;
import lms.identity.department.domain.DepartmentNotFoundException;
import lms.identity.department.domain.DuplicateDepartmentException;
import lms.identity.department.domain.InvalidDepartmentException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "lms.identity.department")
public class DepartmentExceptionHandler {

    @ExceptionHandler(DepartmentNotFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(DepartmentNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "DEPARTMENT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(DuplicateDepartmentException.class)
    ResponseEntity<ErrorResponse> handleDuplicate(DuplicateDepartmentException ex) {
        return build(HttpStatus.CONFLICT, "DUPLICATE_DEPARTMENT", ex.getMessage());
    }

    @ExceptionHandler(InvalidDepartmentException.class)
    ResponseEntity<ErrorResponse> handleInvalid(InvalidDepartmentException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_DEPARTMENT", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage()).toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_FAILED", "Dữ liệu không hợp lệ", details));
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }
}

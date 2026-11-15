package lms.identity.accesscontrol.infrastructure.web;

import lms.identity.accesscontrol.domain.AccessControlForbiddenException;
import lms.identity.accesscontrol.domain.AccessControlNotFoundException;
import lms.identity.accesscontrol.domain.DuplicateAccessControlException;
import lms.identity.accesscontrol.domain.InvalidAccessControlException;
import lms.identity.accesscontrol.domain.RoleConflictException;
import lms.common.kernel.ErrorResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "lms.identity.accesscontrol")
public class AccessControlExceptionHandler {

    @ExceptionHandler(AccessControlNotFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(AccessControlNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(AccessControlForbiddenException.class)
    ResponseEntity<ErrorResponse> handleForbidden(AccessControlForbiddenException ex) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage());
    }

    @ExceptionHandler(DuplicateAccessControlException.class)
    ResponseEntity<ErrorResponse> handleDuplicate(DuplicateAccessControlException ex) {
        return build(HttpStatus.CONFLICT, "DUPLICATE", ex.getMessage());
    }

    @ExceptionHandler(RoleConflictException.class)
    ResponseEntity<ErrorResponse> handleRoleConflict(RoleConflictException ex) {
        return build(HttpStatus.CONFLICT, ex.code(), ex.getMessage());
    }

    @ExceptionHandler({InvalidAccessControlException.class, IllegalArgumentException.class})
    ResponseEntity<ErrorResponse> handleInvalid(RuntimeException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ACCESS_CONTROL", ex.getMessage());
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

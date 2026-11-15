package lms.identity.user.infrastructure.web;

import lms.common.kernel.ErrorResponse;
import lms.identity.user.domain.DuplicateReaderException;
import lms.identity.user.domain.DuplicateUserException;
import lms.identity.user.domain.InvalidUserException;
import lms.identity.user.domain.UserNotFoundException;
import lms.identity.user.domain.UserOperationNotAllowedException;
import lms.identity.user.domain.InvalidReaderException;
import lms.identity.user.domain.ReaderProfileNotFoundException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "lms.identity.user")
public class UserExceptionHandler {

    @ExceptionHandler(ReaderProfileNotFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(ReaderProfileNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "READER_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(DuplicateReaderException.class)
    ResponseEntity<ErrorResponse> handleDuplicate(DuplicateReaderException ex) {
        return build(HttpStatus.CONFLICT, "DUPLICATE_READER", ex.getMessage());
    }

    @ExceptionHandler(InvalidReaderException.class)
    ResponseEntity<ErrorResponse> handleInvalid(InvalidReaderException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_READER", ex.getMessage());
    }

    @ExceptionHandler(UserNotFoundException.class)
    ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(UserOperationNotAllowedException.class)
    ResponseEntity<ErrorResponse> handleNotAllowed(UserOperationNotAllowedException ex) {
        return build(HttpStatus.FORBIDDEN, "USER_OPERATION_NOT_ALLOWED", ex.getMessage());
    }

    @ExceptionHandler(DuplicateUserException.class)
    ResponseEntity<ErrorResponse> handleDuplicateUser(DuplicateUserException ex) {
        return build(HttpStatus.CONFLICT, "DUPLICATE_USER", ex.getMessage());
    }

    @ExceptionHandler(InvalidUserException.class)
    ResponseEntity<ErrorResponse> handleInvalidUser(InvalidUserException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_USER", ex.getMessage());
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

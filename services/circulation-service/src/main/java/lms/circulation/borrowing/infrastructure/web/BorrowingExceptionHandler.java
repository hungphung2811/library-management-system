package lms.circulation.borrowing.infrastructure.web;

import lms.circulation.borrowing.domain.BorrowingLimitExceededException;
import lms.circulation.borrowing.domain.BorrowingNotFoundException;
import lms.circulation.borrowing.domain.InvalidBorrowingException;
import lms.common.kernel.ErrorResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "lms.circulation.borrowing")
public class BorrowingExceptionHandler {

    @ExceptionHandler(BorrowingNotFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(BorrowingNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "BORROWING_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(BorrowingLimitExceededException.class)
    ResponseEntity<ErrorResponse> handleLimit(BorrowingLimitExceededException ex) {
        return build(HttpStatus.CONFLICT, "BORROWING_LIMIT_EXCEEDED", ex.getMessage());
    }

    @ExceptionHandler({InvalidBorrowingException.class, IllegalArgumentException.class})
    ResponseEntity<ErrorResponse> handleInvalid(RuntimeException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_BORROWING", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_FAILED", "Dữ liệu không hợp lệ", details));
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }
}

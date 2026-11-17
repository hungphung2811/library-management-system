package lms.catalog.catalog.infrastructure.web;

import lms.catalog.catalog.domain.DuplicateIsbnException;
import lms.catalog.catalog.domain.InvalidTitleException;
import lms.catalog.catalog.domain.TitleNotFoundException;
import lms.catalog.catalog.domain.TitlePreviewTooLongException;
import lms.common.kernel.ErrorResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "lms.catalog.catalog")
public class CatalogExceptionHandler {

    @ExceptionHandler(TitleNotFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(TitleNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "TITLE_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(DuplicateIsbnException.class)
    ResponseEntity<ErrorResponse> handleDuplicate(DuplicateIsbnException ex) {
        return build(HttpStatus.CONFLICT, "DUPLICATE_ISBN", ex.getMessage());
    }

    @ExceptionHandler(TitlePreviewTooLongException.class)
    ResponseEntity<ErrorResponse> handlePreviewTooLong(TitlePreviewTooLongException ex) {
        return build(HttpStatus.BAD_REQUEST, "TITLE_PREVIEW_TOO_LONG", ex.getMessage());
    }

    @ExceptionHandler(InvalidTitleException.class)
    ResponseEntity<ErrorResponse> handleInvalid(InvalidTitleException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TITLE", ex.getMessage());
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

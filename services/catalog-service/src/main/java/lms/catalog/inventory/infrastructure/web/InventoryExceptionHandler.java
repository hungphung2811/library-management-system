package lms.catalog.inventory.infrastructure.web;

import lms.catalog.inventory.domain.CopyNotFoundException;
import lms.catalog.inventory.domain.CopyTitleNotFoundException;
import lms.catalog.inventory.domain.IllegalCopyTransitionException;
import lms.catalog.inventory.domain.DuplicateBarcodeException;
import lms.catalog.inventory.domain.InvalidCopyException;
import lms.catalog.inventory.domain.ShelfLocationInactiveException;
import lms.catalog.inventory.domain.ShelfLocationNotFoundException;
import lms.common.kernel.ErrorResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "lms.catalog.inventory")
public class InventoryExceptionHandler {

    @ExceptionHandler(CopyTitleNotFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(CopyTitleNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "TITLE_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(CopyNotFoundException.class)
    ResponseEntity<ErrorResponse> handleCopyNotFound(CopyNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "COPY_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(IllegalCopyTransitionException.class)
    ResponseEntity<ErrorResponse> handleTransition(IllegalCopyTransitionException ex) {
        return build(HttpStatus.CONFLICT, "COPY_STATUS_CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(DuplicateBarcodeException.class)
    ResponseEntity<ErrorResponse> handleDuplicate(DuplicateBarcodeException ex) {
        return build(HttpStatus.CONFLICT, "DUPLICATE_BARCODE", ex.getMessage());
    }

    @ExceptionHandler(InvalidCopyException.class)
    ResponseEntity<ErrorResponse> handleInvalid(InvalidCopyException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_COPY", ex.getMessage());
    }

    @ExceptionHandler(ShelfLocationNotFoundException.class)
    ResponseEntity<ErrorResponse> handleShelfNotFound(ShelfLocationNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "SHELF_LOCATION_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(ShelfLocationInactiveException.class)
    ResponseEntity<ErrorResponse> handleShelfInactive(ShelfLocationInactiveException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "SHELF_LOCATION_INACTIVE", ex.getMessage());
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

package lms.migration.infrastructure.web;

import lms.common.kernel.ErrorResponse;
import lms.migration.domain.MigrationExecutionException;
import lms.migration.domain.UnknownTargetException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "lms.migration")
public class MigrationExceptionHandler {

    @ExceptionHandler(UnknownTargetException.class)
    ResponseEntity<ErrorResponse> handleUnknown(UnknownTargetException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("MIGRATION_TARGET_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(MigrationExecutionException.class)
    ResponseEntity<ErrorResponse> handleExecution(MigrationExecutionException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("MIGRATION_FAILED", ex.getMessage()));
    }
}

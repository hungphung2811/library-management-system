package lms.identity.tenantdata.infrastructure.web;

import lms.common.kernel.ErrorResponse;
import lms.identity.tenantdata.domain.TenantDataExportException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Ánh xạ {@link TenantDataExportException} sang HTTP (ADR-0031 mục 3.2), thân {@code {code, message}}. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantDataExceptionHandler {

    @ExceptionHandler(TenantDataExportException.class)
    ResponseEntity<ErrorResponse> handle(TenantDataExportException ex) {
        HttpStatus status = switch (ex.reason()) {
            case EXPORT_NOT_FOUND, TENANT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case EXPORT_IN_PROGRESS, EXPORT_NOT_READY -> HttpStatus.CONFLICT;
            case EXPORT_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case EXPORT_EXPIRED -> HttpStatus.GONE;
            case EXPORT_NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(ErrorResponse.of(ex.reason().name(), ex.getMessage()));
    }
}

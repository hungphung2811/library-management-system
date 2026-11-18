package lms.identity.apiclient.infrastructure.web;

import java.util.List;
import lms.common.kernel.ErrorResponse;
import lms.identity.apiclient.domain.ApplicationException;
import lms.identity.tenant.application.ApplicationOriginException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Ánh xạ lỗi của API Application sang HTTP, định dạng {@code {code, message}} như hiện có. Thông điệp không chứa secret,
 * tenant chủ của origin trùng, hay thông tin phân biệt "của tenant khác" với "không tồn tại". Lỗi body của ApplicationController
 * do {@link ApplicationRequestValidationHandler} xử lý (giới hạn đúng controller).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApplicationExceptionHandler {

    @ExceptionHandler(ApplicationException.class)
    ResponseEntity<ErrorResponse> handle(ApplicationException ex) {
        HttpStatus status = switch (ex.reason()) {
            case APPLICATION_NOT_FOUND, CREDENTIAL_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_APPLICATION, INVALID_SCOPE -> HttpStatus.BAD_REQUEST;
            case SCOPE_NOT_GRANTABLE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case KIND_MISMATCH, APPLICATION_NAME_DUPLICATED, APPLICATION_LIMIT_EXCEEDED, CREDENTIAL_LIMIT_EXCEEDED,
                 APPLICATION_NOT_ACTIVE, APPLICATION_NOT_SUSPENDED, CREDENTIAL_NOT_ACTIVE, CONTRACT_REQUIRED ->
                    HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(ErrorResponse.of(ex.reason().name(), ex.getMessage()));
    }

    @ExceptionHandler(ApplicationOriginException.class)
    ResponseEntity<ErrorResponse> handleOrigin(ApplicationOriginException ex) {
        return switch (ex.reason()) {
            case INVALID -> ResponseEntity.badRequest().body(ErrorResponse.of("ORIGIN_INVALID", ex.getMessage(),
                    ex.detail() == null ? List.of() : List.of(ex.detail())));
            case DUPLICATED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ErrorResponse.of("ORIGIN_DUPLICATED", ex.getMessage()));
            case LIMIT_EXCEEDED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ErrorResponse.of("ORIGIN_LIMIT_EXCEEDED", ex.getMessage()));
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ErrorResponse.of("APPLICATION_DOMAIN_NOT_FOUND", "Không tìm thấy domain"));
        };
    }
}

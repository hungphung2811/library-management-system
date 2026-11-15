package lms.identity.tenant.infrastructure.web;

import lms.common.kernel.ErrorResponse;
import lms.identity.tenant.application.PlatformAccountException;
import lms.identity.tenant.domain.DuplicateTenantException;
import lms.identity.tenant.domain.InvalidTenantException;
import lms.identity.tenant.domain.NoTenantAccessException;
import lms.identity.tenant.domain.OrganizationNotFoundException;
import lms.identity.tenant.domain.TenantNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * NoTenantAccessException có thể nổi lên từ controller của context khác (đăng nhập, làm mới, đổi tenant ở auth), nên
 * handler này không giới hạn basePackages. Chỉ xử lý đúng các exception của context tenant (không bắt exception chung
 * như lỗi validate) để không nuốt xử lý của context khác; ưu tiên cao để không bị handler chung nuốt.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantExceptionHandler {

    @ExceptionHandler(NoTenantAccessException.class)
    ResponseEntity<ErrorResponse> handleNoTenantAccess(NoTenantAccessException ex) {
        return build(HttpStatus.FORBIDDEN, "NO_TENANT_ACCESS", ex.getMessage());
    }

    @ExceptionHandler(TenantNotFoundException.class)
    ResponseEntity<ErrorResponse> handleTenantNotFound(TenantNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(OrganizationNotFoundException.class)
    ResponseEntity<ErrorResponse> handleOrganizationNotFound(OrganizationNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(DuplicateTenantException.class)
    ResponseEntity<ErrorResponse> handleDuplicate(DuplicateTenantException ex) {
        return build(HttpStatus.CONFLICT, "DUPLICATE_TENANT", ex.getMessage());
    }

    @ExceptionHandler(InvalidTenantException.class)
    ResponseEntity<ErrorResponse> handleInvalid(InvalidTenantException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TENANT", ex.getMessage());
    }

    @ExceptionHandler(PlatformAccountException.class)
    ResponseEntity<ErrorResponse> handleAccount(PlatformAccountException ex) {
        return switch (ex.reason()) {
            case DUPLICATE -> build(HttpStatus.CONFLICT, "ACCOUNT_EXISTS", ex.getMessage());
            case NOT_FOUND -> build(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", ex.getMessage());
            case INVALID -> build(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_ACCOUNT", ex.getMessage());
        };
    }

    private static ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }
}

package lms.identity.tenant.infrastructure.web;

import lms.common.kernel.ErrorResponse;
import lms.identity.tenant.application.PlatformAccountException;
import lms.identity.tenant.domain.DuplicateTenantException;
import lms.identity.tenant.domain.InvalidStorefrontOriginException;
import lms.identity.tenant.domain.InvalidTenantException;
import lms.identity.tenant.domain.InvalidTenantModulesException;
import lms.identity.tenant.domain.NoTenantAccessException;
import lms.identity.tenant.domain.OrganizationNotFoundException;
import lms.identity.tenant.domain.StorefrontOriginDuplicatedException;
import lms.identity.tenant.domain.StorefrontOriginLimitExceededException;
import lms.identity.tenant.domain.StorefrontOriginNotFoundException;
import lms.identity.tenant.domain.TenantModuleRequiredException;
import lms.identity.tenant.domain.TenantNotFoundException;
import lms.identity.tenant.domain.UnknownModuleException;
import java.util.List;
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

    @ExceptionHandler(UnknownModuleException.class)
    ResponseEntity<ErrorResponse> handleUnknownModule(UnknownModuleException ex) {
        return build(HttpStatus.BAD_REQUEST, "UNKNOWN_MODULE", ex.getMessage());
    }

    @ExceptionHandler(InvalidTenantModulesException.class)
    ResponseEntity<ErrorResponse> handleInvalidModules(InvalidTenantModulesException ex) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_MODULES", ex.getMessage());
    }

    @ExceptionHandler(TenantModuleRequiredException.class)
    ResponseEntity<ErrorResponse> handleModuleRequired(TenantModuleRequiredException ex) {
        return build(HttpStatus.CONFLICT, "TENANT_MODULE_REQUIRED", ex.getMessage());
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

    @ExceptionHandler(InvalidStorefrontOriginException.class)
    ResponseEntity<ErrorResponse> handleInvalidOrigin(InvalidStorefrontOriginException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("ORIGIN_INVALID", ex.getMessage(), List.of(ex.reason().name())));
    }

    /** details có tenantId của chủ (chỉ platform admin gọi được endpoint này) khi origin thuộc tenant khác. */
    @ExceptionHandler(StorefrontOriginDuplicatedException.class)
    ResponseEntity<ErrorResponse> handleDuplicatedOrigin(StorefrontOriginDuplicatedException ex) {
        List<String> details = ex.ownerTenantId() == null ? List.of() : List.of("tenantId=" + ex.ownerTenantId());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("ORIGIN_DUPLICATED", ex.getMessage(), details));
    }

    @ExceptionHandler(StorefrontOriginLimitExceededException.class)
    ResponseEntity<ErrorResponse> handleOriginLimit(StorefrontOriginLimitExceededException ex) {
        return build(HttpStatus.CONFLICT, "ORIGIN_LIMIT_EXCEEDED", ex.getMessage());
    }

    @ExceptionHandler(StorefrontOriginNotFoundException.class)
    ResponseEntity<ErrorResponse> handleOriginNotFound(StorefrontOriginNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "STOREFRONT_ORIGIN_NOT_FOUND", ex.getMessage());
    }

    private static ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }
}

package lms.identity.tenant.infrastructure.web;

import lms.common.kernel.ErrorResponse;
import lms.identity.tenant.domain.NoTenantAccessException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * NoTenantAccessException có thể nổi lên từ controller của context khác (đăng nhập, làm mới, đổi tenant ở auth), nên
 * handler này không giới hạn basePackages. Chỉ xử lý đúng exception này; ưu tiên cao để không bị handler chung nuốt.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantExceptionHandler {

    @ExceptionHandler(NoTenantAccessException.class)
    ResponseEntity<ErrorResponse> handleNoTenantAccess(NoTenantAccessException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of("NO_TENANT_ACCESS", ex.getMessage()));
    }
}

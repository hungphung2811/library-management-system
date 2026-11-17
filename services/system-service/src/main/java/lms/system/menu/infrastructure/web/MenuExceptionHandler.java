package lms.system.menu.infrastructure.web;

import lms.common.kernel.ErrorResponse;
import lms.system.menu.domain.MenuErrorCode;
import lms.system.menu.domain.MenuException;
import lms.tenant.TenantRequiredException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "lms.system.menu")
public class MenuExceptionHandler {

    @ExceptionHandler(MenuException.class)
    ResponseEntity<ErrorResponse> handleMenu(MenuException ex) {
        return ResponseEntity.status(statusOf(ex.code())).body(ErrorResponse.of(ex.code().name(), ex.getMessage()));
    }

    static HttpStatus statusOf(MenuErrorCode code) {
        return switch (code) {
            case MENU_NOT_FOUND, MENU_PARENT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case MENU_CODE_DUPLICATED, MENU_HAS_CHILDREN, MENU_VERSION_CONFLICT, MENU_ALREADY_INITIALIZED -> HttpStatus.CONFLICT;
            case MENU_CYCLE, MENU_TOO_DEEP, MENU_PATH_INVALID, MENU_PERMISSION_KEY_INVALID, MENU_LIMIT_EXCEEDED,
                 MENU_MODULE_INVALID, MENU_INVALID -> HttpStatus.BAD_REQUEST;
        };
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        var details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage()).toList();
        return ResponseEntity.badRequest().body(ErrorResponse.of("VALIDATION_ERROR", "Dữ liệu không hợp lệ", details));
    }

    /** Vi phạm ràng buộc DB lọt qua kiểm nghiệp vụ (ví dụ hai quản trị cùng thêm một mã, cha vừa bị xóa). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> handleIntegrity(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("MENU_CONFLICT", "Dữ liệu menu vừa bị thay đổi, hãy tải lại và thử lại"));
    }

    @ExceptionHandler(TenantRequiredException.class)
    ResponseEntity<ErrorResponse> handleTenantRequired(TenantRequiredException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorResponse.of("TENANT_REQUIRED", "Token không thuộc tenant nào"));
    }
}

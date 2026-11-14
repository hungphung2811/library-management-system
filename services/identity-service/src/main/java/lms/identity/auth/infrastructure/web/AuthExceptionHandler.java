package lms.identity.auth.infrastructure.web;

import lms.identity.auth.domain.AccountDisabledException;
import lms.identity.auth.domain.AccountLockedException;
import lms.identity.auth.domain.AuthenticationUnavailableException;
import lms.identity.auth.domain.DuplicateUsernameException;
import lms.identity.auth.domain.InvalidCredentialsException;
import lms.identity.auth.domain.InvalidRefreshTokenException;
import lms.common.kernel.ErrorResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "lms.identity.auth")
public class AuthExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthExceptionHandler.class);

    @ExceptionHandler({InvalidCredentialsException.class, InvalidRefreshTokenException.class})
    ResponseEntity<ErrorResponse> handleUnauthorized(RuntimeException ex) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", ex.getMessage());
    }

    @ExceptionHandler(AccountLockedException.class)
    ResponseEntity<ErrorResponse> handleLocked(AccountLockedException ex) {
        return build(HttpStatus.LOCKED, "ACCOUNT_LOCKED", ex.getMessage());
    }

    @ExceptionHandler(AccountDisabledException.class)
    ResponseEntity<ErrorResponse> handleDisabled(AccountDisabledException ex) {
        return build(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", ex.getMessage());
    }

    @ExceptionHandler(DuplicateUsernameException.class)
    ResponseEntity<ErrorResponse> handleDuplicate(DuplicateUsernameException ex) {
        return build(HttpStatus.CONFLICT, "DUPLICATE_USERNAME", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ErrorResponse> handleInvalid(IllegalArgumentException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(AuthenticationUnavailableException.class)
    ResponseEntity<ErrorResponse> handleUnavailable(AuthenticationUnavailableException ex) {
        log.error("Không phát hành được token", ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE", "Dịch vụ xác thực tạm thời không khả dụng");
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

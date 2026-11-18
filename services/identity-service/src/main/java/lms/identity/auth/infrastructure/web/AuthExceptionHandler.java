package lms.identity.auth.infrastructure.web;

import lms.identity.auth.domain.AccountDisabledException;
import lms.identity.auth.domain.AccountLockedException;
import lms.identity.auth.domain.AuthenticationUnavailableException;
import lms.identity.auth.domain.DuplicateUsernameException;
import lms.identity.auth.domain.ExternalAuthException;
import lms.identity.auth.domain.InvalidCredentialsException;
import lms.identity.auth.domain.InvalidRefreshTokenException;
import lms.identity.auth.domain.InvalidSignupException;
import lms.identity.auth.domain.RefreshTokenSupersededException;
import lms.identity.auth.domain.SessionNotFoundException;
import lms.identity.auth.domain.SessionNotIdentifiedException;
import lms.identity.auth.domain.SignupNotAvailableException;
import lms.identity.auth.domain.WrongCurrentPasswordException;
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

    /** Token vừa được xoay (ân hạn/race): client đọc lại refresh token mới nhất rồi thử lại. Mã riêng, vẫn 401. */
    @ExceptionHandler(RefreshTokenSupersededException.class)
    ResponseEntity<ErrorResponse> handleSuperseded(RefreshTokenSupersededException ex) {
        return build(HttpStatus.UNAUTHORIZED, "REFRESH_TOKEN_SUPERSEDED", ex.getMessage());
    }

    @ExceptionHandler(SessionNotFoundException.class)
    ResponseEntity<ErrorResponse> handleSessionNotFound(SessionNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(SessionNotIdentifiedException.class)
    ResponseEntity<ErrorResponse> handleSessionNotIdentified(SessionNotIdentifiedException ex) {
        return build(HttpStatus.CONFLICT, "SESSION_NOT_IDENTIFIED", ex.getMessage());
    }

    @ExceptionHandler(lms.identity.auth.domain.InvalidPasswordResetTokenException.class)
    ResponseEntity<ErrorResponse> handleInvalidResetToken(lms.identity.auth.domain.InvalidPasswordResetTokenException ex) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_RESET_TOKEN", ex.getMessage());
    }

    @ExceptionHandler(WrongCurrentPasswordException.class)
    ResponseEntity<ErrorResponse> handleWrongCurrentPassword(WrongCurrentPasswordException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "WRONG_CURRENT_PASSWORD", ex.getMessage());
    }

    @ExceptionHandler(ExternalAuthException.class)
    ResponseEntity<ErrorResponse> handleExternal(ExternalAuthException ex) {
        HttpStatus status = switch (ex.reason()) {
            case PROVIDER_NOT_CONFIGURED, EXTERNAL_LOGIN_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case RETURN_URL_NOT_ALLOWED, INVALID_STATE, EXCHANGE_FAILED -> HttpStatus.BAD_REQUEST;
            case PROVIDER_UNAVAILABLE -> HttpStatus.BAD_GATEWAY;
            case ACCOUNT_NOT_LINKED -> HttpStatus.FORBIDDEN;
            case ALREADY_LINKED -> HttpStatus.CONFLICT;
            case INVALID_TICKET -> HttpStatus.UNAUTHORIZED;
        };
        if (status == HttpStatus.BAD_GATEWAY) {
            log.warn("Đăng nhập ngoài: {}", ex.getMessage(), ex);
        }
        return build(status, ex.reason().name(), ex.getMessage());
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

    /** Một mã chung cho mọi lý do không đăng ký được (ADR-0011 2.5): không lộ tenant nào có/không có tự đăng ký. */
    /** Chỉ ném sau khi mật khẩu đúng và chỉ ở kênh storefront. */
    @ExceptionHandler(lms.identity.auth.domain.EmailNotVerifiedException.class)
    ResponseEntity<ErrorResponse> handleEmailNotVerified(lms.identity.auth.domain.EmailNotVerifiedException ex) {
        return build(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED", ex.getMessage());
    }

    /** Một mã và một thông điệp cho mọi lý do xác minh thất bại (không dò được tài khoản). */
    @ExceptionHandler(lms.identity.auth.domain.InvalidEmailVerificationException.class)
    ResponseEntity<ErrorResponse> handleInvalidEmailVerification(
            lms.identity.auth.domain.InvalidEmailVerificationException ex) {
        return build(HttpStatus.BAD_REQUEST, "EMAIL_VERIFICATION_FAILED", ex.getMessage());
    }

    @ExceptionHandler(SignupNotAvailableException.class)
    ResponseEntity<ErrorResponse> handleSignupNotAvailable(SignupNotAvailableException ex) {
        return build(HttpStatus.FORBIDDEN, "SIGNUP_NOT_AVAILABLE", SignupNotAvailableException.MESSAGE);
    }

    @ExceptionHandler(InvalidSignupException.class)
    ResponseEntity<ErrorResponse> handleInvalidSignup(InvalidSignupException ex) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage());
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

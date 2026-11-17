package lms.circulation.policy.infrastructure.web;

import java.util.List;
import lms.circulation.policy.domain.InvalidCirculationPolicyException;
import lms.circulation.policy.domain.PolicyVersionConflictException;
import lms.common.kernel.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "lms.circulation.policy")
public class PolicyExceptionHandler {

    @ExceptionHandler(InvalidCirculationPolicyException.class)
    ResponseEntity<ErrorResponse> handleInvalid(InvalidCirculationPolicyException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ErrorResponse.of("INVALID_CIRCULATION_POLICY", ex.getMessage()));
    }

    @ExceptionHandler(PolicyVersionConflictException.class)
    ResponseEntity<ErrorResponse> handleConflict(PolicyVersionConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("POLICY_VERSION_CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage()).toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("VALIDATION_FAILED", "Dữ liệu không hợp lệ", details));
    }
}

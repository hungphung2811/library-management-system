package lms.circulation.config;

import lms.circulation.fine.domain.FineRuleException;
import lms.circulation.fine.domain.InvalidFineException;
import lms.circulation.policy.domain.CompensationRuleException;
import lms.common.kernel.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Lỗi nghiệp vụ của bậc hỏng, chính sách đền bù, miễn/giảm, hoàn khoản. Advice không giới hạn package vì các lỗi này
 * có thể phát sinh khi trả/báo mất/tìm lại sách (controller của borrowing) lẫn ở controller của fine/policy; advice của
 * borrowing chỉ áp cho controller trong package borrowing và không được phụ thuộc domain của context khác.
 * INVALID 422, NOT_FOUND 404, CONFLICT 409.
 */
@RestControllerAdvice
public class CompensationExceptionHandler {

    @ExceptionHandler(FineRuleException.class)
    ResponseEntity<ErrorResponse> handleFine(FineRuleException ex) {
        return build(switch (ex.kind()) {
            case INVALID -> HttpStatus.UNPROCESSABLE_ENTITY;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
        }, ex.code(), ex.getMessage());
    }

    @ExceptionHandler(CompensationRuleException.class)
    ResponseEntity<ErrorResponse> handleCompensation(CompensationRuleException ex) {
        return build(switch (ex.kind()) {
            case INVALID -> HttpStatus.UNPROCESSABLE_ENTITY;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
        }, ex.code(), ex.getMessage());
    }

    /** Cùng ánh xạ với FineExceptionHandler; cần ở đây vì lỗi này cũng phát sinh khi trả/báo mất (controller borrowing). */
    @ExceptionHandler(InvalidFineException.class)
    ResponseEntity<ErrorResponse> handleInvalidFine(InvalidFineException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_FINE", ex.getMessage());
    }

    private static ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }
}

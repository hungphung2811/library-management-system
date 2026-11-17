package lms.system.cms.infrastructure.web;

import lms.common.kernel.ErrorResponse;
import lms.system.cms.domain.CmsErrorCode;
import lms.system.cms.domain.CmsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Ánh xạ lỗi của context cms. Thông điệp lỗi không bao giờ chứa dữ liệu người gửi (chỉ tên trường/quy tắc), vì
 * customer-api có thể chuyển tiếp.
 */
@RestControllerAdvice(basePackages = "lms.system.cms")
public class CmsExceptionHandler {

    @ExceptionHandler(CmsException.class)
    ResponseEntity<ErrorResponse> handleCms(CmsException ex) {
        return ResponseEntity.status(statusOf(ex.code())).body(ErrorResponse.of(ex.code().name(), ex.getMessage()));
    }

    static HttpStatus statusOf(CmsErrorCode code) {
        return switch (code) {
            case CMS_PAGE_NOT_FOUND, CONTACT_REQUEST_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CMS_SLUG_DUPLICATED, CMS_VERSION_CONFLICT, CONTACT_REQUEST_ANONYMIZED,
                 CONTACT_REQUEST_VERSION_CONFLICT, CONTACT_STATUS_TRANSITION_INVALID -> HttpStatus.CONFLICT;
            case CMS_SLUG_INVALID, CMS_CONTENT_TOO_LONG, CMS_PAGE_INVALID, CONSENT_REQUIRED,
                 CONTACT_REQUEST_INVALID -> HttpStatus.BAD_REQUEST;
        };
    }

    /** Chỉ trả tên trường và quy tắc, không trả giá trị người gửi. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        var details = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage()).toList();
        return ResponseEntity.badRequest().body(ErrorResponse.of("VALIDATION_ERROR", "Dữ liệu không hợp lệ", details));
    }

    /** Query sai kiểu (status lạ, from/to không phải ISO-8601, id không phải UUID). */
    @ExceptionHandler(BindException.class)
    ResponseEntity<ErrorResponse> handleBind(BindException ex) {
        var details = ex.getFieldErrors().stream().map(e -> e.getField() + ": giá trị không hợp lệ").toList();
        return ResponseEntity.badRequest().body(ErrorResponse.of("VALIDATION_ERROR", "Dữ liệu không hợp lệ", details));
    }

    /** JSON hỏng hoặc enum lạ (ví dụ status). Không lộ nội dung thân request. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("VALIDATION_ERROR", "Nội dung yêu cầu không hợp lệ"));
    }
}

package lms.support.notification.infrastructure.web;

import lms.common.kernel.ErrorResponse;
import lms.support.notification.domain.DeliveryNotFoundException;
import lms.support.notification.domain.DeliveryNotRetryableException;
import lms.support.notification.domain.InvalidTemplateException;
import lms.support.notification.domain.PreferenceMandatoryException;
import lms.support.notification.domain.TemplateParamNotAllowedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Lỗi của các API thông báo (quản trị và tùy chọn người dùng); thông điệp không chứa địa chỉ hay dữ liệu cá nhân. */
@RestControllerAdvice(assignableTypes = {NotificationDeliveryController.class, NotificationSettingsController.class,
        NotificationTemplateController.class, NotificationPreferenceController.class})
public class NotificationAdminExceptionHandler {

    @ExceptionHandler(DeliveryNotFoundException.class)
    ResponseEntity<ErrorResponse> notFound(DeliveryNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of("DELIVERY_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(DeliveryNotRetryableException.class)
    ResponseEntity<ErrorResponse> notRetryable(DeliveryNotRetryableException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("DELIVERY_NOT_RETRYABLE", ex.getMessage()));
    }

    @ExceptionHandler(TemplateParamNotAllowedException.class)
    ResponseEntity<ErrorResponse> paramNotAllowed(TemplateParamNotAllowedException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("TEMPLATE_PARAM_NOT_ALLOWED", ex.getMessage()));
    }

    @ExceptionHandler(InvalidTemplateException.class)
    ResponseEntity<ErrorResponse> invalidTemplate(InvalidTemplateException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("TEMPLATE_INVALID", ex.getMessage()));
    }

    @ExceptionHandler(PreferenceMandatoryException.class)
    ResponseEntity<ErrorResponse> mandatory(PreferenceMandatoryException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("PREFERENCE_MANDATORY", ex.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorResponse> invalidBody(Exception ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("VALIDATION_FAILED", "Dữ liệu gửi lên không hợp lệ"));
    }
}

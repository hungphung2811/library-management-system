package lms.api.dashboard.web;

import lms.api.dashboard.service.DashboardException;
import lms.common.kernel.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Lỗi do BFF quyết định khi gộp summary; chỉ áp cho DashboardController, lỗi của service đi theo ApiExceptionHandler. */
@RestControllerAdvice(assignableTypes = DashboardController.class)
public class DashboardExceptionHandler {

    @ExceptionHandler(DashboardException.class)
    ResponseEntity<ErrorResponse> handle(DashboardException ex) {
        return ResponseEntity.status(ex.status()).body(ErrorResponse.of(ex.code(), ex.getMessage()));
    }
}
